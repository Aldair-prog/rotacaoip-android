#!/bin/bash
# Instala os dois apps no emulador, abre, tira print e confere se não fechou sozinho.
set -u
OUT=resultados
mkdir -p "$OUT"
adb wait-for-device
sleep 45 # deixa o Android terminar de iniciar (Play Services)

testar() {
  local NOME=$1 PKG=$2 APK=$3
  echo "=== $NOME ($PKG) ===" | tee -a "$OUT/resumo.txt"
  adb install -r -g "$APK" >>"$OUT/resumo.txt" 2>&1 || { echo "FALHOU INSTALAR" | tee -a "$OUT/resumo.txt"; return; }
  adb logcat -c
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 1.2; adb exec-out screencap -p >"$OUT/$NOME-abertura1.png"
  sleep 0.8; adb exec-out screencap -p >"$OUT/$NOME-abertura2.png"
  for t in 6 14 25; do
    sleep $([ $t = 6 ] && echo 6 || echo 9)
    adb exec-out screencap -p >"$OUT/$NOME-${t}s.png"
  done
  if adb shell pidof "$PKG" >/dev/null; then
    echo "app aberto: OK" | tee -a "$OUT/resumo.txt"
  else
    echo "APP FECHOU" | tee -a "$OUT/resumo.txt"
  fi
  adb logcat -d >"$OUT/$NOME-logcat.txt"
  grep -E "FATAL|AndroidRuntime|ReactNativeJS|AppPush" "$OUT/$NOME-logcat.txt" | head -60 >>"$OUT/resumo.txt"
  # volta e abre de novo (segunda abertura, sem tela de permissão)
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  adb exec-out screencap -p >"$OUT/$NOME-reaberto.png"
  # rola a tela de login para conferir o resto
  adb shell input swipe 540 1700 540 700 400
  sleep 2
  adb exec-out screencap -p >"$OUT/$NOME-rolado.png"
  adb shell dumpsys package "$PKG" | grep -E "versionCode|versionName" | head -2 >>"$OUT/resumo.txt"
}

testar connect br.com.connectmdcpay.app art/apk-connect/ConnectPay-emulador.apk
testar stratton br.com.strattonpay.app art/apk-stratton/Stratton-emulador.apk

echo "=== os dois apps instalados juntos ===" | tee -a "$OUT/resumo.txt"
adb shell pm list packages | grep -E "connectmdcpay|strattonpay" | tee -a "$OUT/resumo.txt"

pushtest() {
  local NOME=$1 PKG=$2 SCHEME=$3
  echo "=== push nativo $NOME ===" | tee -a "$OUT/resumo.txt"
  adb logcat -c
  adb shell am start -a android.intent.action.VIEW -d "$SCHEME://teste-push" "$PKG" >/dev/null 2>&1
  sleep 14
  adb exec-out screencap -p >"$OUT/$NOME-push.png"
  adb shell pidof "$PKG" >/dev/null && echo "app aberto depois do push: OK" | tee -a "$OUT/resumo.txt" || echo "APP FECHOU NO PUSH" | tee -a "$OUT/resumo.txt"
  adb shell dumpsys notification --noredact | grep -E "pkg=$PKG" | head -5 >>"$OUT/resumo.txt"
  adb logcat -d | grep -E "AppPush|FATAL|AndroidRuntime" | head -30 >>"$OUT/resumo.txt"
  adb shell cmd statusbar expand-notifications; sleep 2
  adb exec-out screencap -p >"$OUT/$NOME-notificacoes.png"
  adb shell cmd statusbar collapse
}
pushtest connect br.com.connectmdcpay.app connectpay
pushtest stratton br.com.strattonpay.app strattonpay

abas() {
  local NOME=$1 PKG=$2 SCHEME=$3
  echo "=== telas internas $NOME ===" | tee -a "$OUT/resumo.txt"
  adb shell am force-stop "$PKG"; adb logcat -c
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 8
  adb shell am start -a android.intent.action.VIEW -d "$SCHEME://teste-abas" "$PKG" >/dev/null 2>&1
  sleep 10
  adb exec-out screencap -p >"$OUT/$NOME-inicio.png"
  # passa pelas abas (barra de baixo)
  for x in 324 540 756 972 108; do adb shell input tap $x 2280; sleep 3; adb exec-out screencap -p >"$OUT/$NOME-aba-$x.png"; done
  adb shell pidof "$PKG" >/dev/null && echo "telas internas: OK" | tee -a "$OUT/resumo.txt" || echo "APP FECHOU NAS TELAS" | tee -a "$OUT/resumo.txt"
  adb logcat -d | grep -E "FATAL|AndroidRuntime|Caused by|ReactNativeJS" | head -40 >>"$OUT/resumo.txt"
}
abas connect br.com.connectmdcpay.app connectpay
abas stratton br.com.strattonpay.app strattonpay
cat "$OUT/resumo.txt"
exit 0
