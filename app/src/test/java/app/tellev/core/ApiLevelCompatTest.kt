package app.tellev.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * API 级别护栏：项目 minSdk=31 且未启用核心库脱糖，
 * java.time.LocalDate.ofInstant 与 java.nio.file.Path.of 都是 Android API 34 才加入的方法，
 * 在 API 31–33 上运行期抛 NoSuchMethodError。
 *
 * 宿主 JVM 单测跑在 JDK9+ 上，这些方法都存在，因此测不出这个差距；而 lint 又不是打包门禁
 * （见审查报告 TELLER-3-2），所以这里除语法等价性之外，再用源码门禁把回潮拦住。
 */
class ApiLevelCompatTest {

    @Test fun `epoch millis resolve to the expected local date with API 26 safe calls`() {
        assertEquals(LocalDate.of(1970, 1, 1), localDateOf(0L, ZoneId.of("+08:00")))
        assertEquals(LocalDate.of(2025, 1, 1), localDateOf(1_735_689_600_000L, ZoneId.of("+08:00")))
        assertEquals(LocalDate.of(2026, 1, 1), localDateOf(1_767_225_600_000L, ZoneId.of("+08:00")))
        // 同一时刻换个时区会落到前一天：说明这里是真按 zone 归日，不是把毫秒截断了。
        assertEquals(LocalDate.of(2024, 12, 31), localDateOf(1_735_689_600_000L, ZoneId.of("-05:00")))
        assertEquals(LocalDate.of(2100, 1, 1), localDateOf(4_102_444_800_000L, ZoneId.of("Z")))
    }

    @Test fun `metrics source keeps the API 26 safe date conversion`() {
        val source = codeWithoutComments(sourceOf("core/metrics/GenerationMetrics.kt"))
        assertFalse(
            "LocalDate.ofInstant needs API 34 and throws NoSuchMethodError on API 31-33",
            Regex("""LocalDate\.ofInstant\(""").containsMatchIn(source),
        )
        assertTrue(
            "use Instant.atZone(zone).toLocalDate(), the API 26 equivalent already used in ui/dsh",
            source.contains(".atZone(") && source.contains(".toLocalDate()"),
        )
    }

    @Test fun `preset save keeps the API 26 safe path construction`() {
        val source = codeWithoutComments(sourceOf("feature/chat/ChatViewModel.kt"))
        assertFalse(
            "java.nio.file.Path.of needs API 34 and throws NoSuchMethodError on API 31-33",
            Regex("""Path\.of\(""").containsMatchIn(source),
        )
        assertTrue(source.contains("Paths.get("))
    }

    private fun localDateOf(timestampMs: Long, zoneId: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate()

    /** 剥掉行注释，避免注释里提到 API 34 方法名时被源码门禁误判。 */
    private fun codeWithoutComments(source: String): String =
        source.lineSequence().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment >= 0) line.substring(0, comment) else line
        }

    private fun sourceOf(name: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(dir, "app/src/main/java/app/tellev/$name")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: error("cannot locate $name from ${System.getProperty("user.dir")}")
        }
    }
}
