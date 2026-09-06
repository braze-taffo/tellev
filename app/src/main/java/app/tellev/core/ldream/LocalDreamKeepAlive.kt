package app.tellev.core.ldream

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 观察 [LocalDreamCore.state] 并驱动前台保活服务：引擎启动/生成/模型转换
 * 期间保持服务运行（app 进程不被降到可回收档，核心子进程与已加载模型得以
 * 常驻），状态回落（停止/失败）即撤服务。TellevGraph 创建时调用 [start]，
 * 应用生命周期内常驻。
 */
object LocalDreamKeepAlive {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            LocalDreamCore.state.collect { state ->
                if (state == LocalDreamCoreState.Idle || state is LocalDreamCoreState.Failed) {
                    app.stopService(Intent(app, LocalDreamEngineService::class.java))
                } else {
                    ContextCompat.startForegroundService(
                        app,
                        Intent(app, LocalDreamEngineService::class.java)
                            .setAction(Intent.ACTION_DEFAULT)
                            .putExtra(LocalDreamEngineService.EXTRA_DESC, describe(state)),
                    )
                }
            }
        }
    }

    private fun describe(state: LocalDreamCoreState): String = when (state) {
        is LocalDreamCoreState.Starting -> "正在加载模型：${state.modelDirName}"
        is LocalDreamCoreState.Ready ->
            "模型已常驻内存：${state.modelDirName}（直至退出应用或卸载）"
        is LocalDreamCoreState.Generating ->
            "生成中 ${state.step}/${state.totalSteps} 步：${state.modelDirName}"
        is LocalDreamCoreState.Converting ->
            "模型转换中：${state.line.take(48)}"
        LocalDreamCoreState.Idle, is LocalDreamCoreState.Failed -> "运行中"
    }
}
