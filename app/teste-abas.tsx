import { useEffect } from 'react';
import { View } from 'react-native';
import { Redirect, router } from 'expo-router';
import { E2E } from '../src/e2e';
import { useSession } from '../src/session';
import { C } from '../src/theme';

// Tela técnica do teste automático: abre as abas sem conta para conferir que nada fecha o app
export default function TesteAbas() {
  // Só existe no APK de teste do emulador; no app dos clientes este link não faz nada
  return E2E ? <Abas /> : <Redirect href="/" />;
}

function Abas() {
  const { testSignIn } = useSession();
  useEffect(() => {
    testSignIn();
    setTimeout(() => router.replace('/(tabs)'), 300);
  }, [testSignIn]);
  return <View style={{ flex: 1, backgroundColor: C.bg }} />;
}
