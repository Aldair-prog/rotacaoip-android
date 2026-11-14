(function () {
  if (window.__df) return;
  var B = window.DFApp;
  if (!B) return;

  /* ---------- Downloads (blob:, data:, a[download]) ---------- */
  var blobs = {};
  var origCreate = URL.createObjectURL;
  URL.createObjectURL = function (obj) {
    var u = origCreate.apply(URL, arguments);
    try { if (obj instanceof Blob) blobs[u] = obj; } catch (e) {}
    return u;
  };
  var origRevoke = URL.revokeObjectURL;
  URL.revokeObjectURL = function (u) {
    var args = arguments;
    setTimeout(function () {
      try { origRevoke.apply(URL, args); } catch (e) {}
      delete blobs[u];
    }, 120000);
  };

  var EXT = {
    'application/pdf': 'pdf', 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp',
    'image/gif': 'gif', 'text/plain': 'txt', 'text/csv': 'csv', 'application/json': 'json',
    'application/zip': 'zip', 'text/html': 'html',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx'
  };
  function extFor(mime) { return EXT[(mime || '').split(';')[0].trim()] || 'bin'; }

  var seq = 0;
  function sendBlob(blob, name) {
    var mime = (blob.type || 'application/octet-stream').split(';')[0];
    if (!name) name = 'detetive_' + Date.now() + '.' + extFor(mime);
    else if (name.indexOf('.') < 0) name += '.' + extFor(mime);
    var id = 'f' + (++seq) + '_' + Date.now();
    if (!B.fileStart(id, name, mime)) return;
    var CH = 786432; // múltiplo de 3 -> base64 sem padding no meio
    var off = 0;
    function next() {
      if (off >= blob.size) { B.fileEnd(id); return; }
      var part = blob.slice(off, off + CH);
      off += CH;
      var fr = new FileReader();
      fr.onload = function () {
        var s = fr.result;
        B.fileChunk(id, s.substring(s.indexOf(',') + 1));
        next();
      };
      fr.onerror = function () { B.fileError(id, 'erro de leitura'); };
      fr.readAsDataURL(part);
    }
    next();
  }

  function downloadUrl(url, name) {
    if (blobs[url]) { sendBlob(blobs[url], name); return; }
    fetch(url, { credentials: 'include' })
      .then(function (r) { return r.blob(); })
      .then(function (b) { sendBlob(b, name); })
      .catch(function (e) { B.fileError('', String(e)); });
  }

  function handleAnchor(a) {
    var href = a.href || '';
    if (/^(blob:|data:)/i.test(href)) {
      downloadUrl(href, a.getAttribute('download') || '');
      return true;
    }
    if (a.hasAttribute('download') && /^https?:/i.test(href)) {
      try {
        if (new URL(href).origin === location.origin) {
          downloadUrl(href, a.getAttribute('download') || '');
          return true;
        }
      } catch (e) {}
    }
    return false;
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (handleAnchor(this)) return;
    return origClick.apply(this, arguments);
  };
  var origDispatch = HTMLAnchorElement.prototype.dispatchEvent;
  HTMLAnchorElement.prototype.dispatchEvent = function (ev) {
    if (ev && ev.type === 'click' && handleAnchor(this)) return false;
    return origDispatch.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (a && handleAnchor(a)) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  /* ---------- Impressão / Exportar PDF ---------- */
  window.print = function () { B.print(); };

  var overlay = null;
  function closeOverlay() {
    if (overlay && overlay.parentNode) { overlay.parentNode.removeChild(overlay); overlay = null; return true; }
    overlay = null;
    return false;
  }
  function blankWindow() {
    closeOverlay();
    var wrap = document.createElement('div');
    wrap.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:#fff;display:flex;flex-direction:column';
    var bar = document.createElement('div');
    bar.style.cssText = 'display:flex;gap:8px;justify-content:flex-end;padding:8px;background:#0B0F17';
    var ifr = document.createElement('iframe');
    ifr.style.cssText = 'flex:1;border:0;width:100%;background:#fff';
    wrap.appendChild(bar);
    wrap.appendChild(ifr);
    (document.body || document.documentElement).appendChild(wrap);
    overlay = wrap;
    var w = ifr.contentWindow;
    function html() {
      try { return '<!DOCTYPE html>' + w.document.documentElement.outerHTML; } catch (e) { return ''; }
    }
    function title() { try { return w.document.title || document.title || 'documento'; } catch (e) { return 'documento'; } }
    function btn(t, bg, f) {
      var b = document.createElement('button');
      b.textContent = t;
      b.style.cssText = 'padding:9px 14px;border:0;border-radius:8px;background:' + bg + ';color:#fff;font:600 14px sans-serif';
      b.onclick = f;
      bar.appendChild(b);
    }
    btn('Salvar PDF', '#2563EB', function () { B.printHtml(html(), title()); });
    btn('Fechar', '#374151', closeOverlay);
    try {
      w.print = function () { setTimeout(function () { B.printHtml(html(), title()); }, 300); };
      w.close = closeOverlay;
    } catch (e) {}
    return w;
  }

  var origOpen = window.open;
  window.open = function (url) {
    var s = url == null ? '' : String(url);
    if (/^(blob:|data:)/i.test(s)) { downloadUrl(s, ''); return null; }
    if (s === '' || s === 'about:blank') return blankWindow();
    return origOpen.apply(window, arguments);
  };

  /* ---------- Copiar texto ---------- */
  try {
    if (navigator.clipboard) {
      navigator.clipboard.writeText = function (t) {
        try { B.copy(String(t)); } catch (e) {}
        return Promise.resolve();
      };
    }
  } catch (e) {}
  document.addEventListener('selectstart', function (e) { e.stopImmediatePropagation(); }, true);
  function addStyle() {
    if (document.getElementById('__df_style')) return;
    var s = document.createElement('style');
    s.id = '__df_style';
    s.textContent = 'body,body *:not(input):not(textarea):not(button):not(select){-webkit-user-select:text!important;user-select:text!important;-webkit-touch-callout:default!important}';
    (document.head || document.documentElement).appendChild(s);
  }

  /* ---------- Salvar senha / preencher login ---------- */
  function visible(el) { return !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length); }
  function pwFields() {
    var out = [], l = document.querySelectorAll('input[type=password]');
    for (var i = 0; i < l.length; i++) if (visible(l[i])) out.push(l[i]);
    return out;
  }
  function isUserType(el) {
    var t = (el.getAttribute('type') || 'text').toLowerCase();
    return t === 'text' || t === 'email' || t === 'tel' || t === 'number';
  }
  function findUser(pw) {
    var scope = pw.form || document;
    var ins = scope.querySelectorAll('input'), cand = null, passed = false, after = null;
    for (var i = 0; i < ins.length; i++) {
      var el = ins[i];
      if (el === pw) { passed = true; continue; }
      if (!isUserType(el) || !visible(el)) continue;
      if (!passed) cand = el; else if (!after) after = el;
    }
    return cand || after;
  }
  var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  function setVal(el, v) {
    setter.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  var credCache = undefined, credAt = 0;
  function creds() {
    if (credCache !== undefined && Date.now() - credAt < 5000) return credCache;
    try { credCache = JSON.parse(B.getCredentials() || 'null'); } catch (e) { credCache = null; }
    credAt = Date.now();
    return credCache;
  }
  function autofill() {
    var pws = pwFields();
    if (pws.length !== 1) return; // ignora telas de cadastro (senha + confirmar)
    var pw = pws[0];
    if (pw.__dfFilled || pw.value || pw.getAttribute('autocomplete') === 'new-password') return;
    var c = creds();
    if (!c || !c.p) return;
    pw.__dfFilled = true;
    var u = findUser(pw);
    if (u && !u.value && c.u) setVal(u, c.u);
    setVal(pw, c.p);
  }

  var pending = null;
  function capture() {
    var pws = pwFields();
    if (pws.length !== 1 || !pws[0].value) return;
    var u = findUser(pws[0]);
    pending = { u: u ? u.value : '', p: pws[0].value };
  }
  function flush() {
    if (!pending) return;
    var c = pending; pending = null; credCache = undefined;
    try { B.offerSave(c.u, c.p); } catch (e) {}
  }
  document.addEventListener('submit', capture, true);
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('button,input[type=submit],input[type=button],[role=button],a')) capture();
  }, true);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT') capture();
  }, true);
  window.addEventListener('pagehide', flush, true);

  setInterval(function () {
    // só oferece salvar depois que o campo de senha some (login deu certo)
    if (pending && pwFields().length === 0) flush();
    autofill();
  }, 800);

  function onReady() { addStyle(); autofill(); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', onReady);
  else onReady();

  window.__df = { downloadUrl: downloadUrl, closeOverlay: closeOverlay };
})();
