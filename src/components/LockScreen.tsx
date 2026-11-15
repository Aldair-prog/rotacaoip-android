import React, { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { C, FILL } from '../theme';
import { useSession } from '../session';
import { greeting } from '../lib/format';
import { Button, Glow, failure } from './ui';
import { A, LOGO } from '../brand';

/** Tela de entrada com Face ID. Aparece ao abrir o app quando a biometria está ligada. */
export function LockScreen() {
  const { unlock, biometricLabel, signOut, lastEmail, company } = useSession();
  const ins = useSafeAreaInsets();
  const [failed, setFailed] = useState(false);

  const tryUnlock = async () => {
    const ok = await unlock();
    if (!ok) {
      failure();
      setFailed(true);
    }
  };

  useEffect(() => {
    const t = setTimeout(tryUnlock, 400);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const who = company?.trade_name || company?.name || lastEmail;

  return (
    <View style={[FILL, styles.wrap, { paddingTop: ins.top + 60, paddingBottom: ins.bottom + 24 }]}>
      <Glow height={520} strength={0.3} />

      <View style={{ alignItems: 'center' }}>
        <Image source={LOGO} style={styles.logo} contentFit="contain" />
        <Text style={styles.hello}>{greeting()}</Text>
        {who ? (
          <Text style={styles.who} numberOfLines={1}>
            {who}
          </Text>
        ) : null}
      </View>

      <Pressable onPress={tryUnlock} style={styles.faceBtn} accessibilityLabel={`Entrar com ${biometricLabel}`}>
        <View style={styles.faceRing}>
          <Ionicons name="scan-outline" size={54} color={C.text} />
        </View>
        <Text style={styles.faceText}>{failed ? 'Toque para tentar de novo' : `Entrar com ${biometricLabel}`}</Text>
      </Pressable>

      <View style={{ width: '100%', paddingHorizontal: 24, gap: 6 }}>
        <Button title={`Entrar com ${biometricLabel}`} icon="scan" onPress={tryUnlock} />
        <Button title="Entrar com e-mail e senha" variant="plain" onPress={signOut} />
      </View>
    </View>
  );
}

/** Esconde saldo e dados quando o app vai pro seletor de apps */
export function PrivacyShield() {
  return (
    <View style={[FILL, styles.wrap, { justifyContent: 'center' }]}>
      <Glow height={520} strength={0.3} />
      <Image source={LOGO} style={styles.logo} contentFit="contain" />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { backgroundColor: C.bg, alignItems: 'center', justifyContent: 'space-between', zIndex: 100 },
  logo: { width: 110, height: 101 },
  hello: { color: C.muted, fontSize: 16, marginTop: 22 },
  who: { color: C.text, fontSize: 24, fontWeight: '800', marginTop: 4, letterSpacing: -0.4, maxWidth: 320 },
  faceBtn: { alignItems: 'center' },
  faceRing: {
    width: 116,
    height: 116,
    borderRadius: 34,
    borderWidth: 1.5,
    borderColor: A(0.55),
    backgroundColor: A(0.10),
    alignItems: 'center',
    justifyContent: 'center',
  },
  faceText: { color: C.sub, fontSize: 15, fontWeight: '600', marginTop: 14 },
});
