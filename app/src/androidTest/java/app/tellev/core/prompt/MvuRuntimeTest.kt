package app.tellev.core.prompt

import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.TellevGraph
import app.tellev.core.extension.*
import app.tellev.core.storage.CharacterImporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MvuRuntimeTest {
    @org.junit.Before fun launchForegroundActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, app.tellev.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    @Test fun daoYuanRunsActualMvuAndZodInAndroidWebView() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val raw = instrumentation.context.assets.open("dao.json").bufferedReader().use { it.readText() }
        val card = CharacterImporter().importFromJson(raw)
        val graph = TellevGraph.create(instrumentation.targetContext)
        val host = graph.extensionHost as WebViewJsExtensionHost
        val data = Json.parseToJsonElement(raw).jsonObject["data"]!!.jsonObject
        val chat = mutableListOf(buildJsonObject {
            put("name", card.name); put("mes", card.firstMessage); put("swipe_id",0)
            put("is_user",false); put("is_system",false)
            put("swipes",buildJsonArray { add(card.firstMessage) }); put("variables",JsonArray(emptyList()))
        })
        val lock = Any()
        host.setContextProvider(object : ExtensionContextProvider {
            override fun snapshot() = synchronized(lock) { buildJsonObject {
                put("chat", JsonArray(chat.toList())); put("chatId","instrumentation"); put("name2",card.name)
                put("characterWorldBooks",buildJsonArray { add("dao") }); put("globalWorldBooks",JsonArray(emptyList()))
                put("worldBooks",buildJsonArray { add(buildJsonObject {
                    put("name","dao"); put("entries",data["character_book"]!!.jsonObject["entries"]!!)
                }) })
            } }
            override suspend fun setChatMessages(messages: JsonArray, options: JsonObject): Boolean = synchronized(lock) {
                messages.forEach { element ->
                    val m=element.jsonObject; val index=m["message_id"]!!.jsonPrimitive.int
                    val old=chat[index]
                    chat[index]=buildJsonObject {
                        old.forEach { (k,v)->put(k,v) }
                        m["message"]?.let { put("mes",it) }
                        m["swipes_data"]?.let { put("variables",it) }
                    }
                }; true
            }
        })
        host.setMessageVariableBackend(object : MessageVariableBackend {
            override fun messageCount() = synchronized(lock) { chat.size }
            override fun messageVariables(index: Int) = synchronized(lock) {
                chat[index]["variables"]?.jsonArray?.getOrNull(0) as? JsonObject
            }
            override fun lastIndexWithVariables() = synchronized(lock) {
                chat.indexOfLast { !it["variables"]!!.jsonArray.isEmpty() }
            }
            override fun replaceMessageVariables(index: Int, variables: JsonObject) = synchronized(lock) {
                chat[index]=JsonObject(chat[index] + ("variables" to JsonArray(listOf(variables))))
            }
        })
        val id="mvu-instrumentation"
        graph.permissionManager.grantPermission(id,ExtensionPermission.Storage)
        try {
            host.load(ExtensionManifest(id=id,permissions=setOf(ExtensionPermission.Storage)),
                CharacterTavernHelperScripts.buildIsolatedScriptSource(card))
            val initial=host.evaluateRuntime(id,"getVariables({type:'message',message_id:0}).stat_data.主角.生命")
            assertEquals("100", initial)
            synchronized(lock) { chat.add(buildJsonObject {
                put("name",card.name);put("is_user",false);put("swipe_id",0)
                put("variables",JsonArray(emptyList()));put("swipes",JsonArray(emptyList()))
                put("mes","测试回复。<UpdateVariable><JSONPatch>[{\"op\":\"delta\",\"path\":\"/主角/生命\",\"value\":-15}]</JSONPatch></UpdateVariable>")
            }) }
            host.emit(ExtensionEvent("message_received",payload=buildJsonObject {
                put("args",buildJsonArray { add(1);add("normal") })
            }))
            assertEquals("85",host.evaluateRuntime(id,"getVariables({type:'message',message_id:1}).stat_data.主角.生命"))
            assertEquals("100",host.evaluateRuntime(id,"getVariables({type:'message',message_id:0}).stat_data.主角.生命"))
        } finally { host.unload(id) }
    }
}
