import React, { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { WebView } from 'react-native-webview';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { impact } from '../src/components/ui';
import { C } from '../src/theme';

/** Abre PDFs (Termos, Política de privacidade) dentro do app, com X no canto superior direito */
export default function Documento() {
  const { url, title } = useLocalSearchParams<{ url: string; title?: string }>();
  const ins = useSafeAreaInsets();
  const [loading, setLoading] = useState(true);

  return (
    <View style={{ flex: 1, backgroundColor: '#fff' }}>
      <View style={[st.bar, { paddingTop: ins.top + 8 }]}>
        <Text style={st.title} numberOfLines={1}>
          {title || 'Documento'}
        </Text>
        <Pressable
          accessibilityLabel="Fechar"
          hitSlop={12}
          onPress={() => {
            impact();
            router.back();
          }}
          style={({ pressed }) => [st.close, { opacity: pressed ? 0.7 : 1 }]}
        >
          <Ionicons name="close" size={22} color="#fff" />
        </Pressable>
      </View>
      <WebView
        source={{ uri: String(url) }}
        style={{ flex: 1, backgroundColor: '#fff' }}
        onLoadEnd={() => setLoading(false)}
        originWhitelist={['https://*']}
        pullToRefreshEnabled
      />
      {loading ? (
        <View style={st.loading} pointerEvents="none">
          <ActivityIndicator color={C.red} />
        </View>
      ) : null}
    </View>
  );
}

const st = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 18,
    paddingBottom: 12,
    backgroundColor: C.bg,
  },
  title: { flex: 1, color: C.text, fontSize: 17, fontWeight: '700', marginRight: 12 },
  close: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: 'rgba(255,255,255,0.12)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  loading: { position: 'absolute', top: 120, left: 0, right: 0, alignItems: 'center' },
});
