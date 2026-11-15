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
cat "$OUT/resumo.txt"
exit 0
