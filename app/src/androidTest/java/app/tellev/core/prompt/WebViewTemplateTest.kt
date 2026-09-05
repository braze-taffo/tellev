package app.tellev.core.prompt

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewTemplateTest {
    @org.junit.Before fun launchForegroundActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, app.tellev.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    @Test fun realChromiumEvaluatesAsyncEjsWithBundledDependencies() = runBlocking {
        val evaluator = WebViewTemplateEvaluator(InstrumentationRegistry.getInstrumentation().targetContext)
        val result = evaluator.evaluateAsync(buildJsonObject {
            put("template", "<% const n = await Promise.resolve(21); setvar('answer', n * 2); %><%= getvar('answer') %>")
        })
        assertEquals("42", result["content"]!!.jsonPrimitive.content)
        assertEquals(42, result["local"]!!.jsonObject["answer"]!!.jsonPrimitive.int)
    }
}
