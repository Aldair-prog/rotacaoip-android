import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { AppPush } from '../modules/app-push';
import { C } from '../src/theme';

// Tela técnica usada só no teste automático do Android (abre por link e confere o push nativo)
export default function TestePush() {
  const [msg, setMsg] = useState('testando...');
  useEffect(() => {
    (async () => {
      try {
        const P = AppPush;
        if (!P) return setMsg('PUSH: sem modulo');
        const sub = P.getSubscription();
        P.notify('Pix recebido R$ 10,00', 'De TESTE AUTOMATICO');
        P.setSubscribed(true);
        const ok = await P.sendTest();
        setTimeout(() => P.setSubscribed(false), 8000);
        setMsg(`PUSH OK ${sub.endpoint.startsWith('https://ntfy.sh/') ? 'endpoint' : 'sem-endpoint'} teste=${ok}`);
      } catch (e: any) {
        setMsg('PUSH ERRO ' + String(e?.message ?? e));
      }
    })();
  }, []);
  return (
    <View style={{ flex: 1, backgroundColor: C.bg, alignItems: 'center', justifyContent: 'center', padding: 30 }}>
      <Text style={{ color: C.text, fontSize: 18, textAlign: 'center' }}>{msg}</Text>
    </View>
  );
}
