import { useEffect } from 'react';
import { View } from 'react-native';
import { router } from 'expo-router';
import { useSession } from '../src/session';
import { C } from '../src/theme';

// Tela técnica do teste automático: abre as abas sem conta para conferir que nada fecha o app
export default function TesteAbas() {
  const { testSignIn } = useSession();
  useEffect(() => {
    testSignIn();
    setTimeout(() => router.replace('/(tabs)'), 300);
  }, [testSignIn]);
  return <View style={{ flex: 1, backgroundColor: C.bg }} />;
}
