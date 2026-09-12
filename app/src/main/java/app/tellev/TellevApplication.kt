package app.tellev

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/**
 * 全局图片加载器：把磁盘缓存上限从 Coil 默认的 250MB 收紧到 128MB。
 * 聊天气泡按全尺寸解码大图曾把 cacheDir/image_cache 吃满，这部分被计入
 * 系统设置里显示的应用数据体积，也是长期用户数据膨胀感的一部分。
 */
class TellevApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .build()
}
