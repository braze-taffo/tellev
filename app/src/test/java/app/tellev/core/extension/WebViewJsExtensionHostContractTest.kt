package app.tellev.core.extension

import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewJsExtensionHostContractTest {
    private val shim = WebViewJsExtensionHost.TAVERN_HELPER_CONTRACT_OVERRIDES

    @Test
    fun `variable scope contract is explicit`() {
        assertTrue(shim.contains("?'chat'"))
        assertTrue(shim.contains("type==='chat'||type==='local'"))
        assertTrue(shim.contains("type==='global'"))
        assertTrue(shim.contains("script:{},character:{},preset:{},message:{},extension:{}"))
        assertTrue(shim.contains("stGetVariablesForScope"))
        assertTrue(shim.contains("stSetVariablesForScope"))
        assertTrue(shim.contains("stGetAllVariables"))
    }

    @Test
    fun `prompt injection supports upstream arrays and cleanup handle`() {
        assertTrue(shim.contains("!Array.isArray(promptsOrId)"))
        assertTrue(shim.contains("prompts.forEach"))
        assertTrue(shim.contains("return{uninject:uninject}"))
        assertTrue(shim.contains("if(!Array.isArray(ids))ids=[ids]"))
        assertTrue(shim.contains("event_types.GENERATION_ENDED"))
        assertTrue(shim.contains("event_types.GENERATION_STOPPED"))
        assertTrue(shim.contains("typeof p.filter==='function'"))
        assertTrue(shim.contains("Dynamic injectPrompts filter is not supported"))
        assertTrue(shim.contains("p.should_scan"))
        assertTrue(shim.contains("stInjectPromptWithOptions"))
    }

    @Test
    fun `event source emission uses the native cross-extension bridge`() {
        assertTrue(shim.contains("eventSource.emit=function"))
        assertTrue(shim.contains("emitFromEventSource"))
        assertTrue(shim.contains("return _fireLocal(n,args)"))
        assertTrue(shim.contains("JSON.stringify({args:args})"))
    }
}
