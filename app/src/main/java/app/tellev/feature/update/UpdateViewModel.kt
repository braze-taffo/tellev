package app.tellev.feature.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.storage.AppPreferences
import app.tellev.core.update.UpdateChecker
import app.tellev.core.update.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUiState(
    val checking: Boolean = false,
    /** Latest release; meaningful only when it is newer than the installed version. */
    val pendingUpdate: UpdateInfo? = null,
    val upToDate: Boolean = false,
    val error: String? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    /** Set after the install intent has been launched (APK downloaded + handed off). */
    val installStarted: Boolean = false,
)

/**
 * Activity-scoped ViewModel that checks GitHub for a newer tellev release and,
 * when the user taps update, downloads the APK and hands it to the system
 * PackageInstaller. The launch check runs once per cold start via
 * [checkOnLaunch]; [checkNow] is the manual, unguarded entry point.
 */
class UpdateViewModel(
    private val appContext: Context,
    private val checker: UpdateChecker,
    private val preferences: AppPreferences,
    private val currentVersion: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var launchCheckDone = false

    /**
     * Checks once per process lifetime: survives config changes and
     * recomposition (LaunchedEffect re-runs), re-checks on the next cold
     * start so a pending update is surfaced on every app open.
     */
    fun checkOnLaunch() {
        if (launchCheckDone) return
        launchCheckDone = true
        checkNow()
    }

    fun checkNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(checking = true, error = null, upToDate = false) }
            try {
                val latest = checker.fetchLatest(UpdateChecker.DEFAULT_MIRRORS)
                preferences.lastUpdateCheckEpochMs = System.currentTimeMillis()
                if (latest != null && checker.isUpdateAvailable(currentVersion, latest)) {
                    _uiState.update { it.copy(checking = false, pendingUpdate = latest) }
                } else {
                    _uiState.update { it.copy(checking = false, pendingUpdate = null, upToDate = true) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(checking = false, error = e.message ?: "检查更新失败") }
            }
        }
    }

    /**
     * Downloads the pending update's APK (mirror fallback, integrity-checked)
     * and launches the system installer. The APK is written to the cache dir
     * and exposed via FileProvider under the `${packageName}.fileprovider`
     * authority.
     */
    fun downloadAndInstall() {
        val info = _uiState.value.pendingUpdate ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(downloading = true, progress = 0f, error = null) }
            try {
                val target = File(appContext.cacheDir, "tellev-update.apk")
                checker.downloadApk(info, UpdateChecker.DEFAULT_MIRRORS, target) { p ->
                    _uiState.update { it.copy(progress = p) }
                }
                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    target,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                _uiState.update { it.copy(downloading = false, installStarted = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(downloading = false, error = "下载失败：${e.message}") }
            }
        }
    }
}

class UpdateViewModelFactory(
    private val appContext: Context,
    private val checker: UpdateChecker,
    private val preferences: AppPreferences,
    private val currentVersion: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UpdateViewModel::class.java)) {
            return UpdateViewModel(appContext, checker, preferences, currentVersion) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
