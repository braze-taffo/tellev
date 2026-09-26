// Message-document compat layer, injected into every message WebView ahead of
// chat.js / message.js.
//
// SillyTavern runs interactive message HTML — preset and card "frontends" — in
// the page that owns the chat input, so their scripts fill the composer by
// writing straight into `#send_textarea` (the real ST input element). Tellev
// renders each message in its own WebView, where that element does not exist:
// `document.querySelector('#send_textarea')` returned null and every
// click-to-fill UI (梦鲸思客's 思客大调查 option cards, `$('#send_textarea').val(…)`
// style helpers) silently did nothing.
//
// This file keeps the same contract: a `<textarea id="send_textarea">` exists in
// the message document, its `value` reads the live app composer and writes back
// through the bridge. Writes are routed through the normal message request
// channel because the composer is Compose state and may only be mutated on the
// main thread; reads answer synchronously from the WebView bridge.
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
  window.__tellevRequest = request;
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
  window.getChatMessages = function(messageId, options) {
    return request('getChatMessages', { messageId: messageId, options: options || {} });
  };
  window.setChatMessage = function(message, messageId, options) {
    return request('setChatMessage', { message: String(message == null ? '' : message), messageId: messageId, options: options || {} });
  };
  window.TavernHelper = window.TavernHelper || {};
  window.TavernHelper.getVariables = variables;
  window.TavernHelper.getAllVariables = variables;
  window.TavernHelper.triggerSlash = window.triggerSlash;
  window.TavernHelper.getLorebooks = window.getLorebooks;
  window.TavernHelper.createLorebook = window.createLorebook;
  window.TavernHelper.createLorebookEntry = window.createLorebookEntry;
  window.TavernHelper.getChatMessages = window.getChatMessages;
  window.TavernHelper.setChatMessage = window.setChatMessage;
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

  // ── jQuery-lite ────────────────────────────────────────────────────────────
  // Frontends are written against ST's jQuery. `$(element).val(…).trigger('input')`
  // and `$('#x')` are the common shapes, so the shim accepts selectors, elements,
  // node lists and arrays, and chains like jQuery. A card that ships its own
  // jQuery still wins: it is parsed after this file and overwrites window.$.
  function toNodes(selector) {
    if (selector == null || typeof selector === 'function') return [];
    if (typeof selector === 'string') return Array.prototype.slice.call(document.querySelectorAll(selector));
    if (selector.nodeType) return [selector];
    if (typeof selector.length === 'number') return Array.prototype.slice.call(selector);
    return [];
  }
  function firstNode(nodes) { return nodes.length ? nodes[0] : undefined; }
  function forEachNode(nodes, fn) {
    for (var i = 0; i < nodes.length; i++) { if (fn.call(nodes[i], i, nodes[i]) === false) break; }
  }
  function dispatchNamedEvent(node, name) {
    var bubbles = name !== 'focus' && name !== 'blur' && name !== 'focusin' && name !== 'focusout';
    var event;
    try { event = new Event(name, { bubbles: bubbles, cancelable: true }); }
    catch (_) { return false; }
    return node.dispatchEvent(event);
  }
  function TellevSelection(nodes) {
    this.nodes = nodes;
    this.length = nodes.length;
    for (var i = 0; i < nodes.length; i++) this[i] = nodes[i];
  }
  TellevSelection.prototype.each = function(fn) { forEachNode(this.nodes, fn); return this; };
  TellevSelection.prototype.get = function(index) { return this.nodes[index]; };
  TellevSelection.prototype.eq = function(index) { return new TellevSelection(this.nodes.slice(index, index + 1)); };
  TellevSelection.prototype.first = function() { return this.eq(0); };
  TellevSelection.prototype.last = function() { return this.eq(this.nodes.length - 1); };
  TellevSelection.prototype.toArray = function() { return this.nodes.slice(); };
  TellevSelection.prototype.text = function(value) {
    if (value === undefined) { var node = firstNode(this.nodes); return node ? node.textContent : ''; }
    return this.each(function(_, el) { el.textContent = value == null ? '' : String(value); });
  };
  TellevSelection.prototype.html = function(value) {
    if (value === undefined) { var node = firstNode(this.nodes); return node ? node.innerHTML : ''; }
    return this.each(function(_, el) { el.innerHTML = value == null ? '' : String(value); });
  };
  TellevSelection.prototype.val = function(value) {
    if (value === undefined) { var node = firstNode(this.nodes); return node ? node.value : ''; }
    return this.each(function(_, el) { if ('value' in el) el.value = value; });
  };
  TellevSelection.prototype.attr = function(name, value) {
    if (typeof name === 'object' && name !== null) {
      var attributes = name;
      return this.each(function(_, el) {
        Object.keys(attributes).forEach(function(key) { el.setAttribute(key, String(attributes[key])); });
      });
    }
    if (value === undefined) {
      var node = firstNode(this.nodes);
      return node ? node.getAttribute(name) : undefined;
    }
    return this.each(function(_, el) {
      if (value === null) el.removeAttribute(name); else el.setAttribute(name, String(value));
    });
  };
  TellevSelection.prototype.removeAttr = function(name) {
    return this.each(function(_, el) { el.removeAttribute(name); });
  };
  TellevSelection.prototype.prop = function(name, value) {
    if (value === undefined) { var node = firstNode(this.nodes); return node ? node[name] : undefined; }
    return this.each(function(_, el) { el[name] = value; });
  };
  TellevSelection.prototype.css = function(name, value) {
    if (typeof name === 'object' && name !== null) {
      return this.each(function(_, el) { Object.keys(name).forEach(function(key) { el.style.setProperty(key, name[key]); }); });
    }
    if (value === undefined) {
      var node = firstNode(this.nodes);
      return node ? node.style.getPropertyValue(name) : '';
    }
    return this.each(function(_, el) { el.style.setProperty(name, value); });
  };
  TellevSelection.prototype.addClass = function(names) {
    var list = String(names || '').split(/\s+/).filter(Boolean);
    return this.each(function(_, el) { list.forEach(function(name) { el.classList.add(name); }); });
  };
  TellevSelection.prototype.removeClass = function(names) {
    var list = String(names || '').split(/\s+/).filter(Boolean);
    return this.each(function(_, el) { list.forEach(function(name) { el.classList.remove(name); }); });
  };
  TellevSelection.prototype.toggleClass = function(names, force) {
    var list = String(names || '').split(/\s+/).filter(Boolean);
    return this.each(function(_, el) {
      list.forEach(function(name) {
        if (force === true) el.classList.add(name);
        else if (force === false) el.classList.remove(name);
        else el.classList.toggle(name);
      });
    });
  };
  TellevSelection.prototype.hasClass = function(name) {
    var node = firstNode(this.nodes);
    return !!node && node.classList.contains(name);
  };
  TellevSelection.prototype.is = function(selector) {
    var node = firstNode(this.nodes);
    if (!node) return false;
    if (typeof selector === 'string') return node.matches ? node.matches(selector) : false;
    return this.nodes.indexOf(selector) >= 0;
  };
  function insertContent(nodes, content, atStart) {
    forEachNode(nodes, function(_, el) {
      if (typeof content === 'string') {
        if (atStart) el.insertAdjacentHTML('afterbegin', content);
        else el.insertAdjacentHTML('beforeend', content);
        return;
      }
      toNodes(content).forEach(function(child) {
        el.insertBefore(child, atStart ? el.firstChild : null);
      });
    });
  }
  TellevSelection.prototype.append = function(content) { insertContent(this.nodes, content, false); return this; };
  TellevSelection.prototype.prepend = function(content) { insertContent(this.nodes, content, true); return this; };
  TellevSelection.prototype.empty = function() { return this.each(function(_, el) { el.textContent = ''; }); };
  TellevSelection.prototype.remove = function() {
    return this.each(function(_, el) { if (el.parentNode) el.parentNode.removeChild(el); });
  };
  TellevSelection.prototype.hide = function() { return this.css('display', 'none'); };
  TellevSelection.prototype.show = function() { return this.each(function(_, el) { el.style.removeProperty('display'); }); };
  TellevSelection.prototype.find = function(selector) {
    var found = [];
    this.each(function(_, el) {
      found = found.concat(Array.prototype.slice.call(el.querySelectorAll(selector)));
    });
    return new TellevSelection(found);
  };
  TellevSelection.prototype.closest = function(selector) {
    var node = firstNode(this.nodes);
    while (node && node.nodeType === 1) {
      if (node.matches && node.matches(selector)) return new TellevSelection([node]);
      node = node.parentElement;
    }
    return new TellevSelection([]);
  };
  TellevSelection.prototype.parent = function() {
    var node = firstNode(this.nodes);
    return new TellevSelection(node && node.parentElement ? [node.parentElement] : []);
  };
  TellevSelection.prototype.children = function(selector) {
    var node = firstNode(this.nodes);
    var kids = node ? Array.prototype.slice.call(node.children) : [];
    if (selector) kids = kids.filter(function(child) { return child.matches && child.matches(selector); });
    return new TellevSelection(kids);
  };
  TellevSelection.prototype.filter = function(selector) {
    return new TellevSelection(this.nodes.filter(function(node) { return node.matches && node.matches(selector); }));
  };
  TellevSelection.prototype.on = function(events, handler) {
    var names = String(events || '').split(/\s+/).filter(Boolean);
    return this.each(function(_, el) {
      names.forEach(function(name) { el.addEventListener(name, handler); });
    });
  };
  TellevSelection.prototype.one = function(events, handler) {
    var names = String(events || '').split(/\s+/).filter(Boolean);
    return this.each(function(_, el) {
      names.forEach(function(name) { el.addEventListener(name, handler, { once: true }); });
    });
  };
  TellevSelection.prototype.off = function(events, handler) {
    var names = String(events || '').split(/\s+/).filter(Boolean);
    return this.each(function(_, el) {
      names.forEach(function(name) { el.removeEventListener(name, handler); });
    });
  };
  TellevSelection.prototype.trigger = function(events) {
    var names = String(events || '').split(/\s+/).filter(Boolean);
    return this.each(function(_, el) {
      names.forEach(function(name) { dispatchNamedEvent(el, name); });
    });
  };
  TellevSelection.prototype.click = function(handler) {
    if (handler === undefined) return this.trigger('click');
    return this.on('click', handler);
  };
  TellevSelection.prototype.focus = function() { return this.each(function(_, el) { if (el.focus) el.focus(); }); };
  TellevSelection.prototype.blur = function() { return this.each(function(_, el) { if (el.blur) el.blur(); }); };
  TellevSelection.prototype.ready = function(handler) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', handler, { once: true });
    else handler();
    return this;
  };
  if (!window.$) {
    window.$ = function(selector) {
      if (typeof selector === 'function') {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', selector, { once: true });
        else selector();
        return new TellevSelection([]);
      }
      return new TellevSelection(toNodes(selector));
    };
    window.$.fn = TellevSelection.prototype;
    window.$.each = function(collection, fn) {
      if (collection == null) return collection;
      Object.keys(collection).forEach(function(key) { fn.call(collection[key], key, collection[key]); });
      return collection;
    };
    window.TellevSelection = TellevSelection;
  }

  // ── chat input shim ────────────────────────────────────────────────────────
  var SEND_TEXTAREA_ID = 'send_textarea';
  // Last value this document wrote. Served only when the bridge cannot answer
  // (no TellevMessage, or a rejected read), so multi-step UIs still accumulate.
  var shadowInputValue = '';

  function readComposerInput() {
    try {
      if (window.TellevMessage && typeof window.TellevMessage.getInput === 'function') {
        var current = window.TellevMessage.getInput();
        if (typeof current === 'string') {
          shadowInputValue = current;
          return current;
        }
      }
    } catch (_) {}
    return shadowInputValue;
  }

  function writeComposerInput(text) {
    shadowInputValue = text == null ? '' : String(text);
    try {
      var promise = window.__tellevRequest('setInput', { text: shadowInputValue });
      if (promise && typeof promise.catch === 'function') promise.catch(function() {});
    } catch (_) {}
  }

  function installSendTextarea() {
    if (!document.body) return;
    var area = document.getElementById(SEND_TEXTAREA_ID);
    if (area === null) {
      area = document.createElement('textarea');
      area.id = SEND_TEXTAREA_ID;
      area.name = SEND_TEXTAREA_ID;
      area.className = 'send_textarea';
      area.setAttribute('aria-hidden', 'true');
      area.tabIndex = -1;
      // position:fixed keeps the shim out of the message's flow height and out
      // of the resize/layout scripts' body-children measurement.
      area.style.cssText = 'position:fixed;left:-10000px;top:0;width:1px;height:1px;padding:0;border:0;opacity:0;pointer-events:none;';
      document.body.appendChild(area);
    }
    if (area.__tellevComposerInput) return;
    area.__tellevComposerInput = true;
    Object.defineProperty(area, 'value', {
      configurable: true,
      get: function() { return readComposerInput(); },
      set: function(next) { writeComposerInput(next); return true; }
    });
    // focus() on the off-screen shim would raise the soft keyboard for this
    // message WebView instead of the chat composer the user is typing into.
    area.focus = function() {};
    area.blur = function() {};
  }

  // `SillyTavern.getContext()` is a data snapshot in Tellev; ST also hangs the
  // live command runner off it, and frontends call
  // getContext().executeSlashCommands('/setinput …').
  function bridgeSlashIntoContext() {
    var st = window.SillyTavern;
    if (!st || typeof st.getContext !== 'function' || st.__tellevContextHelpers) return;
    var snapshot = st.getContext;
    st.getContext = function() {
      var context = snapshot.apply(this, arguments) || {};
      if (typeof context.executeSlashCommands !== 'function' && typeof window.triggerSlash === 'function') {
        context.executeSlashCommands = function(commands) { return window.triggerSlash(commands); };
        context.executeSlashCommandsWithOptions = context.executeSlashCommands;
        context.triggerSlash = context.executeSlashCommands;
      }
      return context;
    };
    st.__tellevContextHelpers = true;
  }

  function installMessageHostHelpers() {
    installSendTextarea();
    bridgeSlashIntoContext();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', installMessageHostHelpers, { once: true });
  } else {
    installMessageHostHelpers();
  }
})();
