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
        // message scope is bridged to per-message variables (chat[i].variables[swipe_id])
        assertTrue(shim.contains("stGetMessageVariables"))
        assertTrue(shim.contains("stSetMessageVariables"))
        assertTrue(shim.contains("message_id"))
        // _bind internal APIs are wired to public functions (js-slash-runner index.ts:219-265)
        assertTrue(shim.contains("TavernHelper._bind={"))
        assertTrue(shim.contains("_getVariables:function(o){return TavernHelper.getVariables(o);}"))
        assertTrue(shim.contains("_registerMacroLike"))
        // js-slash-runner top-level methods promoted from builtin.* (audit M9)
        assertTrue(shim.contains("forEach(function(n){if(TavernHelper.builtin&&TavernHelper.builtin[n]!==undefined)TavernHelper[n]=TavernHelper.builtin[n];})"))
        assertTrue(shim.contains("formatAsDisplayedMessage"))
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
        // Function filters are evaluated once at inject time (ST re-evaluates
        // per generation via getExtensionPrompt's filter callback).
        assertTrue(shim.contains("allowed=Boolean(p.filter())"))
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
