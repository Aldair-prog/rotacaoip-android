import React, { useEffect, useRef } from 'react';
import { View } from 'react-native';
import { router, Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as SplashScreen from 'expo-splash-screen';
import { DarkTheme, ThemeProvider } from '@react-navigation/native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { SessionProvider, useSession } from '../src/session';
import { LockScreen, PrivacyShield } from '../src/components/LockScreen';
import { C } from '../src/theme';
import { useNotificationWatcher } from '../src/lib/notify';

SplashScreen.preventAutoHideAsync().catch(() => {});
SplashScreen.setOptions({ duration: 350, fade: true });

const theme = {
  ...DarkTheme,
  colors: { ...DarkTheme.colors, background: C.bg, card: C.bg, primary: C.red, text: C.text, border: C.border },
};

function Gate() {
  const { status, locked, appActive, isAdmin } = useSession();
  useNotificationWatcher(status === 'signedIn');

  // Conta administrativa: abre o Painel Admin direto ao entrar (uma vez por abertura do app)
  const adminOpened = useRef(false);
  useEffect(() => {
    if (status !== 'signedIn') {
      adminOpened.current = false;
      return;
    }
    if (isAdmin && !locked && !adminOpened.current) {
      adminOpened.current = true;
      const t = setTimeout(() => router.push('/admin'), 1500); // deixa o app carregar antes
      return () => clearTimeout(t);
    }
  }, [status, isAdmin, locked]);

  useEffect(() => {
    if (status !== 'loading') SplashScreen.hideAsync().catch(() => {});
  }, [status]);

  if (status === 'loading') return <View style={{ flex: 1, backgroundColor: C.bg }} />;

  const signedIn = status === 'signedIn';
  return (
    <View style={{ flex: 1, backgroundColor: C.bg }}>
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: C.bg } }}>
        <Stack.Protected guard={signedIn}>
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="cobranca/[id]" options={{ presentation: 'modal' }} />
          <Stack.Screen name="saque/[id]" options={{ presentation: 'modal' }} />
          <Stack.Screen name="pagar-qr" options={{ presentation: 'modal' }} />
          <Stack.Screen name="admin" options={{ presentation: 'fullScreenModal' }} />
          <Stack.Screen name="documento" options={{ presentation: 'fullScreenModal' }} />
          <Stack.Screen name="avisos" options={{ presentation: 'modal' }} />
        </Stack.Protected>
        <Stack.Protected guard={!signedIn}>
          <Stack.Screen name="login" options={{ animation: 'fade' }} />
        </Stack.Protected>
      </Stack>
      {signedIn && locked ? <LockScreen /> : null}
      {signedIn && !locked && !appActive ? <PrivacyShield /> : null}
    </View>
  );
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <ThemeProvider value={theme}>
        <SessionProvider>
          <StatusBar style="light" />
          <Gate />
        </SessionProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  );
}
