"""Conversa com o WebView do app (versão de teste) pelo DevTools para inspecionar o painel."""
import json
import sys
import time
import urllib.request

import websocket

MODE = sys.argv[1] if len(sys.argv) > 1 else "inspecionar"


def page_ws():
    for _ in range(10):
        try:
            targets = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=10))
            pages = [t for t in targets if t.get("type") == "page" and t.get("webSocketDebuggerUrl")]
            if pages:
                return pages[0]["webSocketDebuggerUrl"]
        except Exception as e:  # noqa: BLE001
            print("aguardando devtools:", e, file=sys.stderr)
        time.sleep(2)
    raise SystemExit("DevTools do WebView não respondeu")


ws = websocket.create_connection(page_ws(), timeout=30, suppress_origin=True)
_id = 0


def ev(expr):
    global _id
    _id += 1
    ws.send(json.dumps({
        "id": _id,
        "method": "Runtime.evaluate",
        "params": {"expression": expr, "returnByValue": True, "awaitPromise": True},
    }))
    while True:
        msg = json.loads(ws.recv())
        if msg.get("id") == _id:
            res = msg.get("result", {})
            if "exceptionDetails" in res:
                return {"erro": res["exceptionDetails"].get("text")}
            return res.get("result", {}).get("value")


INSPECT = r"""
(function(){
  function pick(el, keys){ var o = {}; keys.forEach(function(k){ var v = el.getAttribute(k); if (v) o[k] = v; }); return o; }
  return {
    url: location.href,
    titulo: document.title,
    ajudante_login_injetado: !!window.__strattonLoginHelper,
    funcao_login_digital: typeof window.__strattonLogin,
    campos: Array.from(document.querySelectorAll('input')).map(function(i){ return pick(i, ['type','name','id','placeholder','autocomplete']); }),
    botoes: Array.from(document.querySelectorAll('button, input[type=submit]')).map(function(b){ return {type: b.type, texto: (b.textContent || b.value || '').trim().slice(0, 40)}; }),
    formularios: document.forms.length,
    fonte_body: getComputedStyle(document.body).fontFamily,
    cor_fundo: getComputedStyle(document.body).backgroundColor,
    largura: window.innerWidth,
    meta_viewport: (document.querySelector('meta[name=viewport]') || {}).content || null
  };
})()
"""

FILL = r"""
(function(){
  if (!window.__strattonLogin) return {resultado: 'função de login ausente'};
  var r = window.__strattonLogin('teste@exemplo.com', 'SenhaDeTeste123', false);
  var p = document.querySelector('input[type=password]');
  var u = document.querySelector('input[type=email]') || document.querySelector('input[type=text]');
  return {resultado: r, usuario_no_campo: u ? u.value : null, senha_preenchida: p ? p.value.length > 0 : false};
})()
"""

out = ev(INSPECT if MODE == "inspecionar" else FILL)
print(json.dumps(out, indent=2, ensure_ascii=False))
