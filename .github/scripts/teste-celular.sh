#!/usr/bin/env bash
# Teste automático do app Stratton num celular Android virtual.
# Gera prints e relatórios na pasta testes/.
set -u
PKG=${1:-br.com.strattonpay.app}
APK=${2:-saida/Stratton-teste.apk}
OUT=${3:-testes}
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

echo "== Instalando"
ls -la saida
adb install -r -g "$APK" | tee "$OUT/instalacao.txt"
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
echo "== Esperando o celular virtual terminar de iniciar (Google Play Services)"
sleep 60
adb logcat -c

echo "== Tempo de abertura (frio)"
adb shell am start -W -n "$PKG/br.com.strattonpay.app.MainActivity" | tee "$OUT/tempo-abertura.txt"
sleep 1
shot 01-abertura

echo "== Esperando o painel carregar"
sleep 25
shot 02-painel

echo "== Inspecionando a tela de login do painel"
PID=$(adb shell pidof "$PKG" | tr -d '\r')
echo "PID=$PID"
adb forward tcp:9222 "localabstract:webview_devtools_remote_$PID"
python3 .github/scripts/devtools.py inspecionar > "$OUT/03-login-inspecao.json" 2>&1
cat "$OUT/03-login-inspecao.json"

echo "== Testando preenchimento de usuário e senha"
python3 .github/scripts/devtools.py preencher > "$OUT/04-login-preenchimento.json" 2>&1
cat "$OUT/04-login-preenchimento.json"
sleep 1
shot 04-login-preenchido

echo "== Testando o login em 2 etapas (e-mail -> Continuar)"
python3 .github/scripts/devtools.py continuar > "$OUT/04b-login-continuar.json" 2>&1
sleep 8
PID=$(adb shell pidof "$PKG" | tr -d '\r')
adb forward --remove-all
adb forward tcp:9222 "localabstract:webview_devtools_remote_$PID"
shot 04b-login-etapa-2
python3 .github/scripts/devtools.py inspecionar > "$OUT/04c-login-etapa-2-inspecao.json" 2>&1
cat "$OUT/04b-login-continuar.json" "$OUT/04c-login-etapa-2-inspecao.json"
adb shell input keyevent 4
sleep 3

echo "== Abrindo Configurações"
python3 .github/scripts/tocar.py "Configurações" | tee "$OUT/05-toque.txt"
sleep 2
shot 05-configuracoes
adb shell input keyevent 4
sleep 1

echo "== Configuração inicial (forçada pelo teste)"
adb shell am start -n "$PKG/br.com.strattonpay.app.MainActivity" --ez test_setup true
sleep 3
shot 06-configuracao-inicial
adb shell input keyevent 4
sleep 1

echo "== Botão voltar e recarregar"
python3 .github/scripts/tocar.py "Recarregar" | tee -a "$OUT/05-toque.txt"
sleep 6
shot 07-depois-de-recarregar

echo "== Memória e desempenho"
adb shell dumpsys meminfo "$PKG" | head -45 > "$OUT/memoria.txt"
adb shell dumpsys gfxinfo "$PKG" | head -30 > "$OUT/desempenho.txt"
ls -la saida/*.apk > "$OUT/tamanho-apk.txt"

echo "== Reabrindo (morno) para medir"
adb shell input keyevent 3
sleep 2
adb shell am start -W -n "$PKG/br.com.strattonpay.app.MainActivity" | tee -a "$OUT/tempo-abertura.txt"

echo "== Erros"
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in" "$OUT/logcat.txt" > "$OUT/erros.txt"; then
  grep -A 25 "FATAL EXCEPTION" "$OUT/logcat.txt" >> "$OUT/erros.txt" || true
  echo "TRAVAMENTO ENCONTRADO"
  cat "$OUT/erros.txt"
else
  echo "Nenhum travamento (crash) ou ANR encontrado." > "$OUT/erros.txt"
fi
grep -iE "chromium.*(Uncaught|error)|Console" "$OUT/logcat.txt" | head -80 > "$OUT/erros-javascript.txt" || true
cat "$OUT/erros.txt"
exit 0
