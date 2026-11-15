import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Linking, Pressable, StyleSheet, View } from 'react-native';
import { router } from 'expo-router';
import { WebView } from 'react-native-webview';
import { Image } from 'expo-image';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { impact } from '../src/components/ui';
import { C } from '../src/theme';
import { adoptRefreshToken, getDevice, getSession, setPanelOpen } from '../src/lib/api';
import { useSession } from '../src/session';
import { BRAND, LOGO } from '../src/brand';

// Algumas opções são só do iPhone e não estão nos tipos do pacote
const WebViewAny = WebView as any;

const HOST = BRAND.host;
const ADMIN_URL = `https://${HOST}/admin/dashboard`;
const PANEL_BG = '#0E1015'; // fundo do painel, pra não aparecer "borda" de navegador

/**
 * Entra já logado: o app passa a própria sessão (mesmo formato que o painel guarda
 * no navegador) e, se o painel renovar o token, o app adota o novo pra não deslogar.
 * Também deixa a página com cara de app: sem zoom, sem seleção de texto, sem o "pulo" do Safari.
 */
function bridge(refreshToken: string | null, email: string, deviceId: string, fingerprint: string) {
  const store = refreshToken ? JSON.stringify({ state: { refreshToken, otpSessionToken: null, email }, version: 0 }) : null;
  return `
(function () {
  try {
    if (${JSON.stringify(store)} && sessionStorage.getItem('cp_bridge') !== '1') {
      sessionStorage.setItem('cp_bridge', '1');
      localStorage.setItem('AuthenticationStore', ${JSON.stringify(store)});
      localStorage.setItem('vexa:device-id', ${JSON.stringify(deviceId)});
      localStorage.setItem('vexa:device-fingerprint', ${JSON.stringify(fingerprint)});
    }
  } catch (e) {}

  function nativeFeel() {
    try {
      var vp = document.querySelector('meta[name=viewport]');
      if (!vp) { vp = document.createElement('meta'); vp.name = 'viewport'; document.head.appendChild(vp); }
      vp.content = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover';
      if (!document.getElementById('cp-native')) {
        var st = document.createElement('style');
        st.id = 'cp-native';
        st.textContent =
          '*{-webkit-tap-highlight-color:transparent!important;-webkit-touch-callout:none}' +
          'body{-webkit-user-select:none;user-select:none}' +
          'html,body,*{-webkit-overflow-scrolling:touch}' +
          'input,textarea,[contenteditable]{-webkit-user-select:text;user-select:text}' +
          '::-webkit-scrollbar{display:none}';
        document.head.appendChild(st);
      }
    } catch (e) {}
  }
  document.addEventListener('DOMContentLoaded', nativeFeel);
  nativeFeel();

  // Tocar na logo do painel (menu lateral / topo) volta pro app do usuário
  document.addEventListener('click', function (e) {
    try {
      var img = e.target && e.target.closest && e.target.closest('img');
      if (!img) return;
      var r = img.getBoundingClientRect();
      var looksLogo = /logo|uploads\/files/i.test((img.getAttribute('src') || '') + ' ' + (img.getAttribute('alt') || ''));
      if (looksLogo && r.top < 260) {
        e.preventDefault();
        e.stopPropagation();
        window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'exit' }));
      }
    } catch (err) {}
  }, true);

  var last = ${JSON.stringify(refreshToken)};
  function sync() {
    try {
      var s = JSON.parse(localStorage.getItem('AuthenticationStore') || '{}');
      var rt = s && s.state && s.state.refreshToken;
      if (rt && rt !== last) {
        last = rt;
        window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'refreshToken', value: rt }));
      }
    } catch (e) {}
  }
  setInterval(sync, 1500);
  window.addEventListener('pagehide', sync);
})();
true;`;
}

export default function Admin() {
  const ins = useSafeAreaInsets();
  const web = useRef<WebView>(null);
  const [progress, setProgress] = useState(0);
  const { lastEmail } = useSession();
  const leave = () => {
    impact();
    if (router.canGoBack()) router.back();
    else router.replace('/');
  };

  useEffect(() => {
    setPanelOpen(true);
    return () => setPanelOpen(false);
  }, []);

  const injected = useMemo(() => {
    const d = getDevice();
    return bridge(getSession()?.refreshToken ?? null, lastEmail, d.id, d.fingerprint);
    // calcula uma vez ao abrir
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <View style={{ flex: 1, backgroundColor: PANEL_BG, paddingTop: ins.top }}>
      {progress < 1 ? <View style={[st.progress, { top: ins.top, width: `${Math.max(progress, 0.08) * 100}%` }]} /> : null}

      <WebViewAny
        ref={web}
        source={{ uri: ADMIN_URL }}
        style={{ flex: 1, backgroundColor: PANEL_BG }}
        containerStyle={{ backgroundColor: PANEL_BG }}
        injectedJavaScriptBeforeContentLoaded={injected}
        onMessage={(e: any) => {
          try {
            const m = JSON.parse(e.nativeEvent.data);
            if (m?.type === 'refreshToken' && typeof m.value === 'string') adoptRefreshToken(m.value);
            if (m?.type === 'exit') leave();
          } catch {}
        }}
        originWhitelist={['https://*']}
        sharedCookiesEnabled
        domStorageEnabled
        javaScriptEnabled
        bounces
        pullToRefreshEnabled
        refreshControlLightMode
        decelerationRate="normal"
        overScrollMode="never"
        contentInsetAdjustmentBehavior="never"
        automaticallyAdjustContentInsets={false}
        {...({ allowsLinkPreview: false, dataDetectorTypes: 'none', textInteractionEnabled: true } as any)}
        setSupportMultipleWindows={false}
        onLoadProgress={(e: any) => setProgress(e.nativeEvent.progress)}
        onShouldStartLoadWithRequest={(req: any) => {
          // Fica no painel; links externos (WhatsApp, PDFs de outros sites) abrem fora
          if (req.url.startsWith('about:') || req.url.startsWith('blob:') || req.url.includes(HOST)) return true;
          Linking.openURL(req.url).catch(() => {});
          return false;
        }}
        startInLoadingState
        renderLoading={() => (
          <View style={st.loading}>
            <ActivityIndicator color={C.red} />
          </View>
        )}
      />

      {/* Logo no topo: toca pra voltar pro app do usuário (a logo do menu lateral faz o mesmo) */}
      <Pressable accessibilityLabel="Voltar para o app" onPress={leave} hitSlop={12} style={[st.logoBtn, { top: ins.top + 16 }]}>
        <Image source={LOGO} style={{ width: 38, height: 35 }} contentFit="contain" />
      </Pressable>
    </View>
  );
}

const st = StyleSheet.create({
  progress: { position: 'absolute', left: 0, height: 2, backgroundColor: C.red, zIndex: 5 },
  loading: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', backgroundColor: PANEL_BG },
  logoBtn: { position: 'absolute', alignSelf: 'center', zIndex: 10, padding: 4 },
});
