package app.tellev.core.storage

import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ

/**
 * 图片等大文件不经过 journal，但同样需要「字节先于引用落盘 + 原子可见」：
 * 临时文件写入并 fsync → 原子改名 → fsync 父目录（保证断电后目录项不丢、
 * 中途被杀不留半文件）。与 [JournaledFileWriter] 的 atomicWrite 同一套纪律，
 * 供聊天图片的各条落盘路径共用。
 */
object DurableFileOps {
    fun write(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling(target.fileName.toString() + ".new")
        FileOutputStream(temp.toFile()).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(target.parent)
    }

    private fun syncDirectory(path: Path) {
        // Windows 的 JVM 提供者无法打开目录；目录持久性语义只在真机上验证。
        if (System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) != true) {
            FileChannel.open(path, READ).use { it.force(true) }
        }
    }
}
