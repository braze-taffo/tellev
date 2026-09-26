package app.tellev.feature.chat

import app.tellev.core.model.WorldBookEntry
import app.tellev.core.model.ChatMessage
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

    fun shouldLoad(html: String, allowUpdates: Boolean = true): Boolean {
        if (loadedHtml != null && !allowUpdates) return false
        if (loadedHtml == html) return false
        loadedHtml = html
        return true
    }
}

internal fun shouldForwardWebViewDragToChat(
    canScrollInDirection: Boolean,
): Boolean = !canScrollInDirection

internal fun chatScrollDeltaAtWebViewEdge(
    canScrollInDirection: Boolean,
    fingerDeltaY: Float,
): Float = if (canScrollInDirection) 0f else -fingerDeltaY

internal data class TavernMessageSlashAction(
    val sendText: String? = null,
    val systemText: String? = null,
    val setInputText: String? = null,
    val deleteMessageIndex: Int? = null,
)

internal fun setTavernMessageSwipe(
    message: ChatMessage,
    content: String,
    requestedSwipe: Int?,
): ChatMessage {
    val swipes = message.swipes.ifEmpty { listOf(message.content) }.toMutableList()
    val swipeIndex = requestedSwipe?.coerceAtLeast(0) ?: message.swipeIndex
    while (swipes.size <= swipeIndex) swipes.add(content)
    swipes[swipeIndex] = content
    return message.copy(content = content, swipes = swipes, swipeIndex = swipeIndex.coerceIn(0, swipes.lastIndex))
}

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

    val send = Regex("""(?s)^/send\s+(.+?)(?:${TRIGGER_PIPE_TAIL})?$""").find(trimmed)
    if (send != null) {
        return TavernMessageSlashAction(sendText = send.groupValues[1].trim())
    }

    val system = Regex("""(?s)^/sys\s+(.+?)(?:\s*\|\s*/cut\s+(\d+))?(?:${TRIGGER_PIPE_TAIL})?$""")
        .find(trimmed)
    if (system != null) {
        return TavernMessageSlashAction(
            systemText = system.groupValues[1].trim(),
            deleteMessageIndex = system.groupValues[2].toIntOrNull(),
        )
    }

    // Some cards feed raw prompt text directly into the slash-command pipe instead of
    // prefixing it with /sys: "prompt | /cut <messageId> | /trigger".
    val pipedPrompt = Regex("""(?s)^(.+?)(?:\s*\|\s*/cut\s+(\d+))?${TRIGGER_PIPE_TAIL}$""")
        .find(trimmed)
    if (pipedPrompt != null && !pipedPrompt.groupValues[1].trimStart().startsWith("/")) {
        return TavernMessageSlashAction(
            systemText = pipedPrompt.groupValues[1].trim(),
            deleteMessageIndex = pipedPrompt.groupValues[2].toIntOrNull(),
        )
    }


    return TavernMessageSlashAction()
}

/**
 * The trailing `/trigger` step of a card-authored pipe, tolerating the command
 * tail that follows it.
 *
 * `/trigger` accepts named arguments (`await=true`, `quiet=true`, …) and can
 * carry a group member, so cards chain `/send <text> | /trigger await=true`.
 * Matching only a bare `/trigger` left that tail unmatched, and since the text
 * capture is lazy it then swallowed the whole pipe: the sent message became
 * `<text>\n| /trigger await=true` instead of `<text>`.
 */
private const val TRIGGER_PIPE_TAIL = """\s*\|\|?\s*/trigger\b[\s\S]*"""

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
        selective = raw.boolean("selective") ?: true,
        constant = constant,
        priority = raw.int("priority") ?: 0,
        insertionOrder = raw.int("order") ?: raw.int("insertion_order") ?: 100,
        depth = raw.int("depth") ?: 4,
        position = position,
        probability = raw.int("probability") ?: 100,
        useProbability = raw.boolean("useProbability") ?: true,
        selectiveLogic = raw.int("selectiveLogic") ?: 0,
        role = raw.int("role") ?: 0,
        matchWholeWords = raw.boolean("matchWholeWords") ?: false,
        useRegex = raw.boolean("useRegex") ?: false,
        caseSensitive = raw.boolean("caseSensitive") ?: false,
        comment = raw.string("comment").orEmpty(),
        excludeRecursion = raw.boolean("exclude_recursion") ?: raw.boolean("excludeRecursion") ?: false,
        preventRecursion = raw.boolean("prevent_recursion") ?: raw.boolean("preventRecursion") ?: false,
        delayUntilRecursion = raw.int("delay_until_recursion") ?: raw.int("delayUntilRecursion")
            ?: raw.boolean("delay_until_recursion")?.let { if (it) 1 else 0 }
            ?: raw.boolean("delayUntilRecursion")?.let { if (it) 1 else 0 }
            ?: 0,
        ignoreBudget = raw.boolean("ignore_budget") ?: raw.boolean("ignoreBudget") ?: false,
        raw = raw,
    )
}

internal fun tavernMessageLayoutScript(nativeViewportHeight: Int = 0): String = """
    (function() {
      var nativeViewportHeight = ${nativeViewportHeight.coerceAtLeast(0)};
      var activeScreenOwner = null;

      function findNestedScrollOwner(target) {
        var body = document.body;
        var doc = document.documentElement;
        var node = target && target.nodeType === 1 ? target : target && target.parentElement;
        while (node && node !== body && node !== doc) {
          var style = window.getComputedStyle(node);
          var overflowY = style.overflowY;
          if ((overflowY === 'auto' || overflowY === 'scroll') &&
              node.scrollHeight > node.clientHeight + 1) {
            return node;
          }
          node = node.parentElement;
        }
        return null;
      }

      function installNestedScrollBridge() {
        if (window.__tellevNestedScrollBridgeInstalled) return;
        window.__tellevNestedScrollBridgeInstalled = true;
        var owner = null;
        var lastTouchY = 0;
        var samples = [];
        var forwardedLastMove = false;

        document.addEventListener('touchstart', function(event) {
          var touch = event.touches && event.touches[0];
          lastTouchY = touch ? touch.screenY : 0;
          owner = findNestedScrollOwner(event.target);
          samples = [{ y: lastTouchY, time: event.timeStamp }];
          forwardedLastMove = false;
          try { TellevBridge.setNestedScrollGesture(!!owner); } catch (_) {}
        }, { capture: true, passive: true });

        document.addEventListener('touchmove', function(event) {
          var touch = event.touches && event.touches[0];
          if (!touch) return;
          // screenY stays stable when the outer list moves the WebView.
          var fingerDeltaY = touch.screenY - lastTouchY;
          lastTouchY = touch.screenY;
          samples.push({ y: lastTouchY, time: event.timeStamp });
          while (samples.length > 2 && event.timeStamp - samples[0].time > 100) samples.shift();
          forwardedLastMove = false;
          if (!owner || fingerDeltaY === 0) return;

          var node = owner;
          var canScrollInDirection = false;
          // A nested scroller's edge is not necessarily the document's edge.
          while (node) {
            var canScrollUp = node.scrollTop > 1;
            var canScrollDown = node.scrollTop + node.clientHeight < node.scrollHeight - 1;
            if (fingerDeltaY > 0 ? canScrollUp : canScrollDown) {
              canScrollInDirection = true;
              break;
            }
            node = findNestedScrollOwner(node.parentElement);
          }
          var root = document.scrollingElement || document.documentElement;
          if (root && (fingerDeltaY > 0 ? root.scrollTop > 1 :
              root.scrollTop + root.clientHeight < root.scrollHeight - 1)) {
            canScrollInDirection = true;
          }
          if (!canScrollInDirection) {
            forwardedLastMove = true;
            // Compose scroll distances use physical pixels; DOM touch uses CSS pixels.
            if (event.cancelable) event.preventDefault();
            try { TellevBridge.forwardBoundaryDrag(-fingerDeltaY * (window.devicePixelRatio || 1)); } catch (_) {}
          }
        }, { capture: true, passive: false });

        function finishTouch(event) {
          if (event.type === 'touchend' && owner && forwardedLastMove && samples.length > 1) {
            var first = samples[0], last = samples[samples.length - 1];
            var duration = event.timeStamp - first.time;
            if (duration > 0 && event.timeStamp - last.time < 100) {
              var velocity = -(last.y - first.y) * 1000 / duration * (window.devicePixelRatio || 1);
              try { TellevBridge.forwardBoundaryFling(velocity); } catch (_) {}
            }
          }
          owner = null;
          samples = [];
          // Keep ownership latched through native ACTION_UP; the next DOWN resets it.
        }
        document.addEventListener('touchend', finishTouch, { capture: true, passive: true });
        document.addEventListener('touchcancel', finishTouch, { capture: true, passive: true });
      }

      function resetMessageViewport() {
        var body = document.body;
        var doc = document.documentElement;
        if (!body || !doc) return;

        var viewportHeight = nativeViewportHeight > 0
          ? nativeViewportHeight
          : Math.max(window.innerHeight || 0, doc.clientHeight || 0);
        var activeScreen = body.querySelector('.screen.active');
        var activeScreenChanged = activeScreen !== activeScreenOwner;
        activeScreenOwner = activeScreen;
        if (activeScreen && viewportHeight > 0) {
          // Preserve the card's own page layout and make the active page the
          // bounded scroll container it was authored to be. Native WebView
          // cannot see this element's scroll edges, so the touch bridge above
          // reports only its boundary deltas to the outer chat list.
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
          body.style.setProperty('overflow-y', 'auto', 'important');
        }

        // Reset only when the card actually switches pages. Observing every
        // class mutation without this guard makes ordinary form selections
        // unexpectedly jump back to the beginning of a long page.
        if (activeScreenChanged) {
          window.scrollTo(0, 0);
          body.scrollTop = 0;
          doc.scrollTop = 0;
          if (activeScreen) activeScreen.scrollTop = 0;
        }
      }

      window.__tellevResetMessageViewport = resetMessageViewport;
      installNestedScrollBridge();
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
