package app.tellev.core.ldream

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地生图核心（Local Dream 的 libstable_diffusion_core.so，MNN OpenCL 后端）
 * 的进程管理：以 `--type sd15cpu` 拉起子进程监听 127.0.0.1，健康检查后就绪；
 * 模型转换走同二进制的 `--convert` 模式。核心二进制与转换骨架资产随 APK 打包
 * （jniLibs/arm64-v8a 与 assets/ldcvt），来源与许可证见 assets/ldcvt/NOTICE.txt。
 */
sealed interface LocalDreamCoreState {
    data object Idle : LocalDreamCoreState

    data class Starting(val modelDirName: String) : LocalDreamCoreState

    data class Ready(val modelDirName: String, val port: Int) : LocalDreamCoreState

    data class Generating(val modelDirName: String, val step: Int, val totalSteps: Int) : LocalDreamCoreState

    data class Converting(val modelDirName: String, val line: String) : LocalDreamCoreState

    data class Failed(val reason: String) : LocalDreamCoreState
}

/**
 * 应用级单例（模式对齐 core.sd.SdNativeEngine）。TellevGraph 启动时用
 * [initialize] 注入 nativeLibraryDir 与模型根目录；聊天与设置两侧共享同一份
 * 进程状态。
 */
object LocalDreamCore {
    private const val TAG = "tellev-ld"
    private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"

    /** st-data 下本地 MNN 模型根目录（每个模型一个子目录）。 */
    const val LOCAL_DREAM_MODELS_DIR_NAME = "user/models-mnn"

    /** 转换骨架资产目录（APK assets 内）。 */
    private const val CVT_ASSET_DIR = "ldcvt"
    private val CVT_ASSET_FILES = listOf(
        "tokenizer.json",
        "clip_skip_1.mnn",
        "clip_skip_2.mnn",
        "unet.mnn",
        "vae_decoder.mnn",
        "vae_encoder.mnn",
    )

    private val coreMutex = Mutex()
    private val processRef = AtomicReference<Process?>(null)

    @Volatile private var nativeLibDir: File? = null
    @Volatile private var servingModelDir: File? = null
    @Volatile private var servingPort: Int = -1

    private val _state = MutableStateFlow<LocalDreamCoreState>(LocalDreamCoreState.Idle)
    val state: StateFlow<LocalDreamCoreState> = _state.asStateFlow()

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun initialize(nativeLibDir: File) {
        this.nativeLibDir = nativeLibDir
    }

    /** 核心二进制是否随包可用（缺失时本地 MNN 引擎不可用）。 */
    fun isBinaryAvailable(): Boolean = executable().isFile

    fun executable(): File = File(nativeLibDir ?: File("."), EXECUTABLE_NAME)

    /**
     * 核心子进程的库搜索路径。除常规系统/供应商目录外，还要把 Mali 驱动所在的
     * SoC 子目录（如 /vendor/lib64/egl/mt6991）加进来：应用子进程的 linker
     * 命名空间不放行对 /vendor 下驱动的绝对路径 dlopen，libOpenCL 会退回按名
     * 搜索，而搜索不递归——缺这一层 OpenCL 就初始化失败并静默回退 CPU
     * （慢约 8 倍且输出纯噪音）。对齐 Local Dream BackendService 的做法。
     */
    private fun buildCoreLibraryPath(): String {
        val paths = mutableListOf(
            nativeLibDir?.absolutePath,
            "/system/lib64",
            "/vendor/lib64",
            "/vendor/lib64/egl",
        )
        runCatching {
            val mali = File("/system/vendor/lib64/egl/libGLES_mali.so")
            if (mali.exists()) {
                val segments = mali.canonicalPath.split("/")
                val soc = segments.getOrNull(segments.size - 2)
                if (!soc.isNullOrBlank()) {
                    listOf("/vendor/lib64/$soc", "/vendor/lib64/egl/$soc").forEach { path ->
                        if (!paths.contains(path)) paths.add(path)
                    }
                }
            }
        }
        return paths.filterNotNull().joinToString(":")
    }

    /**
     * 确保核心进程针对 [modelDir] 运行，返回端口。已在跑同一模型时直接复用；
     * 模型变更时先停旧进程。失败抛 [IllegalStateException]。
     */
    suspend fun ensureStarted(modelDir: File): Int = withContext(Dispatchers.IO) {
        coreMutex.withLock {
            if (processRef.get() != null && servingModelDir == modelDir && servingPort > 0) {
                return@withLock servingPort
            }
            stopLocked()

            val dirName = modelDir.name
            if (!isBinaryAvailable()) {
                val msg = "核心二进制缺失：$EXECUTABLE_NAME"
                _state.value = LocalDreamCoreState.Failed(msg)
                throw IllegalStateException(msg)
            }
            if (!File(modelDir, "unet.mnn.weight").isFile) {
                val msg = "模型未完成转换：$dirName"
                _state.value = LocalDreamCoreState.Failed(msg)
                throw IllegalStateException(msg)
            }

            _state.value = LocalDreamCoreState.Starting(dirName)
            val port = findFreePort()
            val pb = ProcessBuilder(
                executable().absolutePath,
                "--type", "sd15cpu",
                "--model_dir", modelDir.absolutePath,
                "--port", port.toString(),
            ).apply {
                directory(nativeLibDir)
                redirectErrorStream(true)
                environment()["LD_LIBRARY_PATH"] = buildCoreLibraryPath()
            }
            val proc = runCatching { pb.start() }.getOrElse { e ->
                val msg = "启动本地生图核心失败：${e.message}"
                _state.value = LocalDreamCoreState.Failed(msg)
                throw IllegalStateException(msg)
            }
            processRef.set(proc)
            servingModelDir = modelDir
            servingPort = port

            // 日志转发：核心的 stdout 是主要诊断通道。
            Thread {
                runCatching {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) Log.i(TAG, line)
                        }
                    }
                }
                // 进程退出（崩溃或被杀）：清引用，状态回落。
                if (processRef.compareAndSet(proc, null)) {
                    servingModelDir = null
                    servingPort = -1
                    if (_state.value !is LocalDreamCoreState.Idle) {
                        _state.value = LocalDreamCoreState.Failed("核心进程已退出")
                    }
                }
            }.apply { isDaemon = true }.start()

            // 健康检查：模型 mmap 加载通常 1s 内完成。
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (proc.exitValueOrNull() != null) {
                    val msg = "核心进程启动即退出（exit=${proc.exitValue()}）"
                    _state.value = LocalDreamCoreState.Failed(msg)
                    throw IllegalStateException(msg)
                }
                val ok = runCatching {
                    healthClient.newCall(
                        Request.Builder().url("http://127.0.0.1:$port/health").build(),
                    ).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) {
                    _state.value = LocalDreamCoreState.Ready(dirName, port)
                    return@withLock port
                }
                delay(250)
            }
            stopLocked()
            _state.value = LocalDreamCoreState.Failed("核心健康检查超时")
            throw IllegalStateException("核心健康检查超时")
        }
    }

    /** 停止核心进程，释放显存/内存占用。 */
    fun stop() {
        if (stopLocked()) _state.value = LocalDreamCoreState.Idle
    }

    /** 适配器在收到 SSE progress 事件时回写引擎状态（供 ChatViewModel 展示）。 */
    fun reportGenerationProgress(modelDirName: String, step: Int, totalSteps: Int) {
        val current = _state.value
        if (current is LocalDreamCoreState.Ready || current is LocalDreamCoreState.Generating) {
            _state.value = LocalDreamCoreState.Generating(modelDirName, step, totalSteps)
        }
    }

    private fun stopLocked(): Boolean {
        val proc = processRef.getAndSet(null) ?: return false
        servingModelDir = null
        servingPort = -1
        runCatching { proc.destroy() }
        return true
    }

    /**
     * 把 assets/ldcvt 的转换骨架铺进 [modelDir]，并按 [clipSkip] 决定
     * clip_v2.mnn 用哪份骨架。assetReader 为空（JVM 测试）时直接返回 false。
     */
    fun stageConversionAssets(
        modelDir: File,
        clipSkip: Int,
        assetReader: (String) -> java.io.InputStream?,
    ): Boolean {
        modelDir.mkdirs()
        for (name in CVT_ASSET_FILES) {
            val target = File(modelDir, name)
            if (target.isFile) continue
            val input = runCatching { assetReader("$CVT_ASSET_DIR/$name") }.getOrNull() ?: return false
            input.use { src ->
                target.outputStream().use { src.copyTo(it) }
            }
        }
        val clipSource = if (clipSkip >= 2) "clip_skip_2.mnn" else "clip_skip_1.mnn"
        val clipTarget = File(modelDir, "clip_v2.mnn")
        if (!clipTarget.isFile) {
            File(modelDir, clipSource).copyTo(clipTarget, overwrite = true)
        }
        return true
    }

    /**
     * 运行 `--convert`：读取 modelDir/model.safetensors 生成 MNN 权重。
     * [onLine] 收到核心输出行用于进度展示；成功返回 true。
     */
    suspend fun convert(
        modelDir: File,
        clipSkip2: Boolean,
        onLine: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        coreMutex.withLock {
            if (!isBinaryAvailable()) return@withLock false
            _state.value = LocalDreamCoreState.Converting(modelDir.name, "转换启动…")
            val pb = ProcessBuilder(
                mutableListOf(
                    executable().absolutePath,
                    "--convert", modelDir.absolutePath,
                ).apply { if (clipSkip2) add("--clip_skip_2") },
            ).apply {
                directory(nativeLibDir)
                redirectErrorStream(true)
                environment()["LD_LIBRARY_PATH"] = buildCoreLibraryPath()
            }
            val proc = runCatching { pb.start() }.getOrElse { return@withLock false }
            val reader: BufferedReader = proc.inputStream.bufferedReader()
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        _state.value = LocalDreamCoreState.Converting(modelDir.name, line)
                        onLine(line)
                    }
                }
            } catch (_: InterruptedIOException) {
                proc.destroy()
                return@withLock false
            }
            val exited = proc.waitFor()
            val finished = File(modelDir, "finished").isFile
            _state.value = LocalDreamCoreState.Idle
            exited == 0 && finished
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

private fun Process.exitValueOrNull(): Int? = try {
    exitValue()
} catch (_: IllegalThreadStateException) {
    null
}
