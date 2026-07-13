package app.tellev.feature.chat

import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class TavernMessageLoadTracker {
    private var loadedHtml: String? = null

    fun shouldLoad(html: String): Boolean {
        if (loadedHtml == html) return false
        loadedHtml = html
        return true
    }
}

internal fun shouldReleaseTavernTouchToParent(
    preferInternalScrolling: Boolean,
    canScrollInDirection: Boolean,
): Boolean = !preferInternalScrolling && !canScrollInDirection

internal data class TavernMessageSlashAction(
    val sendText: String? = null,
    val systemText: String? = null,
    val setInputText: String? = null,
    val deleteMessageIndex: Int? = null,
)

internal fun parseTavernMessageSlashCommand(script: String): TavernMessageSlashAction {
    val trimmed = script.trim()
    val cutOnly = Regex("""^/cut\s+(\d+)\s*$""").find(trimmed)
    if (cutOnly != null) {
        return TavernMessageSlashAction(deleteMessageIndex = cutOnly.groupValues[1].toIntOrNull())
    }

    val setInput = Regex("""(?s)^/setinput\s+(.+?)(?:\s*\|\|?\s*/.*)?$""").find(trimmed)
    if (setInput != null) {
        return TavernMessageSlashAction(setInputText = setInput.groupValues[1].trim())
    }

    val send = Regex("""(?s)^/send\s+(.+?)(?:\s*\|\|?\s*/trigger\s*)?$""").find(trimmed)
    if (send != null) {
        return TavernMessageSlashAction(sendText = send.groupValues[1].trim())
    }

    val system = Regex("""(?s)^/sys\s+(.+?)(?:\s*\|\s*/cut\s+(\d+))?(?:\s*\|\s*/trigger\s*)?$""")
        .find(trimmed)
    if (system != null) {
        return TavernMessageSlashAction(
            systemText = system.groupValues[1].trim(),
            deleteMessageIndex = system.groupValues[2].toIntOrNull(),
        )
    }

    // Some cards feed raw prompt text directly into the slash-command pipe instead of
    // prefixing it with /sys: "prompt | /cut <messageId> | /trigger".
    val pipedPrompt = Regex("""(?s)^(.+?)(?:\s*\|\s*/cut\s+(\d+))?\s*\|\s*/trigger\s*$""")
        .find(trimmed)
    if (pipedPrompt != null && !pipedPrompt.groupValues[1].trimStart().startsWith("/")) {
        return TavernMessageSlashAction(
            systemText = pipedPrompt.groupValues[1].trim(),
            deleteMessageIndex = pipedPrompt.groupValues[2].toIntOrNull(),
        )
    }


    return TavernMessageSlashAction()
}

internal fun decodeEmbeddedJsonValues(element: JsonElement, json: Json = Json): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (_, value) -> decodeEmbeddedJsonValues(value, json) })
    is JsonArray -> JsonArray(element.map { decodeEmbeddedJsonValues(it, json) })
    is JsonPrimitive -> if (!element.isString) {
        element
    } else {
        val content = element.content.trim()
        if (!(content.startsWith("{") && content.endsWith("}")) &&
            !(content.startsWith("[") && content.endsWith("]"))
        ) {
            element
        } else {
            runCatching { json.parseToJsonElement(content) }
                .map { decodeEmbeddedJsonValues(it, json) }
                .getOrDefault(element)
        }
    }
}

internal fun tavernWorldBookEntry(
    raw: JsonObject,
    fallbackId: String,
): WorldBookEntry {
    val type = raw.string("type")
    val position = when (raw.string("position")) {
        "after_character_definition" -> 1
        "before_author_note" -> 2
        "after_author_note" -> 3
        else -> raw.int("position") ?: 0
    }
    val constant = raw.boolean("constant") ?: (type == "constant")
    return WorldBookEntry(
        id = raw.string("uid") ?: raw.string("id") ?: fallbackId,
        keys = raw.stringList("key") + raw.stringList("keys"),
        secondaryKeys = raw.stringList("keysecondary") + raw.stringList("secondary_keys"),
        content = raw.string("content").orEmpty(),
        enabled = raw.boolean("enabled") ?: !(raw.boolean("disable") ?: false),
        selective = raw.boolean("selective") ?: false,
        constant = constant,
        priority = raw.int("priority") ?: 0,
        insertionOrder = raw.int("order") ?: raw.int("insertion_order") ?: 100,
        depth = raw.int("depth") ?: 4,
        position = position,
        probability = raw.int("probability") ?: 100,
        useProbability = raw.boolean("useProbability") ?: false,
        selectiveLogic = raw.int("selectiveLogic") ?: 0,
        role = raw.int("role") ?: 0,
        matchWholeWords = raw.boolean("matchWholeWords") ?: false,
        useRegex = raw.boolean("useRegex") ?: false,
        caseSensitive = raw.boolean("caseSensitive") ?: false,
        comment = raw.string("comment").orEmpty(),
        excludeRecursion = raw.boolean("exclude_recursion") ?: raw.boolean("excludeRecursion") ?: false,
        preventRecursion = raw.boolean("prevent_recursion") ?: raw.boolean("preventRecursion") ?: false,
        delayUntilRecursion = raw.boolean("delay_until_recursion") ?: raw.boolean("delayUntilRecursion") ?: false,
        raw = raw,
    )
}

internal fun tavernMessageCompatScript(): String = """
    <script id="tellev-message-compat">
    (function(){
      var callbacks = Object.create(null);
      var requestCounter = 0;
      function parseJson(value, fallback) { try { return JSON.parse(value); } catch (_) { return fallback; } }
      function request(operation, payload) {
        return new Promise(function(resolve, reject) {
          var id = 'message_' + (++requestCounter) + '_' + Date.now();
          callbacks[id] = { resolve: resolve, reject: reject };
          try { TellevMessage.request(id, operation, JSON.stringify(payload || {})); }
          catch (error) { delete callbacks[id]; reject(error); }
        });
      }
      window.__tellevMessageResolve = function(id, ok, payloadJson) {
        var callback = callbacks[id];
        if (!callback) return;
        delete callbacks[id];
        var payload = parseJson(payloadJson, payloadJson);
        if (ok) callback.resolve(payload); else callback.reject(new Error(payload && payload.error || String(payload)));
      };
      function variables() { return parseJson(TellevMessage.getAllVariables(), {}); }
      window.getVariables = variables;
      window.getAllVariables = variables;
      window.getCurrentMessageId = function() { return TellevMessage.getCurrentMessageId(); };
      window.triggerSlash = function(text) { return request('triggerSlash', { script: String(text || '') }); };
      window.getLorebooks = function() { return request('getLorebooks', {}); };
      window.createLorebook = function(name) { return request('createLorebook', { name: String(name || '') }); };
      window.createLorebookEntry = function(name, entry) { return request('createLorebookEntry', { name: String(name || ''), entry: entry || {} }); };
      window.TavernHelper = window.TavernHelper || {};
      window.TavernHelper.getVariables = variables;
      window.TavernHelper.getAllVariables = variables;
      window.TavernHelper.triggerSlash = window.triggerSlash;
      window.TavernHelper.getLorebooks = window.getLorebooks;
      window.TavernHelper.createLorebook = window.createLorebook;
      window.TavernHelper.createLorebookEntry = window.createLorebookEntry;
      window.SillyTavern = window.SillyTavern || { getContext: function(){ return {}; } };
      if (!window._) {
        function pathParts(path) { return Array.isArray(path) ? path : String(path || '').replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean); }
        window._ = {
          get: function(object, path, fallback) {
            var value = object;
            var parts = pathParts(path);
            for (var i = 0; i < parts.length; i++) { if (value == null) return fallback; value = value[parts[i]]; }
            return value === undefined ? fallback : value;
          },
          set: function(object, path, value) {
            var parts = pathParts(path), cursor = object;
            for (var i = 0; i < parts.length - 1; i++) { if (!cursor[parts[i]] || typeof cursor[parts[i]] !== 'object') cursor[parts[i]] = {}; cursor = cursor[parts[i]]; }
            if (parts.length) cursor[parts[parts.length - 1]] = value;
            return object;
          }
        };
      }
      window.errorCatched = window.errorCatched || function(fn) {
        return function() { try { return fn.apply(this, arguments); } catch (error) { console.error(error); } };
      };
      if (!window.$) {
        window.$ = function(selector) {
          if (typeof selector === 'function') {
            if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', selector, { once: true });
            else selector();
            return;
          }
          var nodes = Array.prototype.slice.call(document.querySelectorAll(String(selector || '')));
          return {
            text: function(value) { nodes.forEach(function(node) { node.textContent = value == null ? '' : String(value); }); return this; },
            html: function(value) { nodes.forEach(function(node) { node.innerHTML = value == null ? '' : String(value); }); return this; },
            css: function(name, value) { nodes.forEach(function(node) { node.style.setProperty(name, value); }); return this; }
          };
        };
      }
    })();
    </script>
""".trimIndent()

internal fun tavernMessageLayoutScript(nativeViewportHeight: Int = 0): String = """
    (function() {
      var nativeViewportHeight = ${nativeViewportHeight.coerceAtLeast(0)};
      function resetMessageViewport() {
        var body = document.body;
        var doc = document.documentElement;
        if (!body || !doc) return;

        var viewportHeight = nativeViewportHeight > 0
          ? nativeViewportHeight
          : Math.max(window.innerHeight || 0, doc.clientHeight || 0);
        var activeScreen = body.querySelector('.screen.active');
        if (activeScreen && viewportHeight > 0) {
          doc.style.setProperty('height', viewportHeight + 'px', 'important');
          body.style.setProperty('height', viewportHeight + 'px', 'important');
          body.style.setProperty('min-height', viewportHeight + 'px', 'important');
          activeScreen.style.setProperty('max-height', Math.max(180, viewportHeight - 16) + 'px', 'important');
          activeScreen.style.setProperty('overflow-y', 'auto', 'important');
        }
        var children = Array.prototype.slice.call(body.children || []);
        var hasOversizedFlowChild = children.some(function(child) {
          var style = window.getComputedStyle(child);
          if (style.display === 'none' || style.position === 'fixed' || style.position === 'absolute') return false;
          var rect = child.getBoundingClientRect();
          return Math.max(child.scrollHeight || 0, child.offsetHeight || 0, rect.height || 0) > viewportHeight + 8;
        });
        var bodyStyle = window.getComputedStyle(body);
        if (hasOversizedFlowChild && (bodyStyle.display === 'flex' || bodyStyle.display === 'inline-flex')) {
          body.style.setProperty('justify-content', 'flex-start', 'important');
          body.style.setProperty('height', 'auto', 'important');
          body.style.setProperty('min-height', Math.max(1, viewportHeight) + 'px', 'important');
          body.style.setProperty('overflow-y', 'auto', 'important');
        }

        window.scrollTo(0, 0);
        body.scrollTop = 0;
        doc.scrollTop = 0;
      }

      window.__tellevResetMessageViewport = resetMessageViewport;
      resetMessageViewport();
      window.requestAnimationFrame(resetMessageViewport);
      setTimeout(resetMessageViewport, 50);
      setTimeout(resetMessageViewport, 300);
      setTimeout(resetMessageViewport, 1000);
      if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(resetMessageViewport).catch(function() {});
      }
      if (window.MutationObserver && document.body && !window.__tellevMessageScreenObserver) {
        window.__tellevMessageScreenObserver = new MutationObserver(resetMessageViewport);
        window.__tellevMessageScreenObserver.observe(document.body, {
          subtree: true,
          attributes: true,
          attributeFilter: ['class']
        });
      }
    })();
""".trimIndent()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull
        ?: string(key)?.toBooleanStrictOrNull()

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull
        ?: string(key)?.toIntOrNull()

private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> value.content.split(',').map(String::trim).filter(String::isNotEmpty)
    else -> emptyList()
}
