import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Animated, Share, StyleSheet, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { Image } from 'expo-image';
import * as Clipboard from 'expo-clipboard';
import { Ionicons } from '@expo/vector-icons';
import { Badge, Button, Card, ErrorBanner, IconButton, KV, Money, Screen, success, txStatus } from '../../src/components/ui';
import { api, ApiError, Transaction } from '../../src/lib/api';
import { brl, dateTime, txDate } from '../../src/lib/format';
import { chargeCache } from '../../src/lib/cache';
import { C } from '../../src/theme';
import { BRAND } from '../../src/brand';

const WAITING = ['PENDING', 'PROCESSING'];

function pickQr(t: Transaction | null) {
  const p = t?.pix ?? {};
  const code: string | undefined = p.qrcode?.code ?? p.qr_code ?? p.qrcode ?? p.code ?? p.emv ?? p.copy_paste;
  const image: string | undefined = p.qrcode?.base64 ?? p.qr_code_base64 ?? p.base64 ?? p.image;
  return { code: typeof code === 'string' ? code : undefined, image: typeof image === 'string' ? image : undefined };
}

export default function Cobranca() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const cached = chargeCache.get(id);
  const [tx, setTx] = useState<Transaction | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const pop = useRef(new Animated.Value(0)).current;
  const wasPaid = useRef(false);

  // Consulta o status e repete a cada 4s enquanto aguarda o pagamento
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const t = await api.transaction(id);
        if (!alive) return;
        setTx(t);
        setError(null);
        if (t.status === 'APPROVED' && !wasPaid.current) {
          wasPaid.current = true;
          success();
          Animated.spring(pop, { toValue: 1, useNativeDriver: true, friction: 5 }).start();
        }
        if (!t.status || WAITING.includes(t.status)) timer = setTimeout(tick, 4000);
      } catch (e) {
        if (!alive) return;
        setError(e instanceof ApiError ? e.message : 'Não foi possível consultar a cobrança.');
        timer = setTimeout(tick, 8000);
      }
    };
    tick();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [id, pop]);

  const fromTx = pickQr(tx);
  const code = cached?.code ?? fromTx.code;
  const image = cached?.image ?? fromTx.image;
  const amount = tx?.amount ?? cached?.amount ?? 0;
  const info = txStatus(tx?.status ?? (cached ? 'PENDING' : undefined));
  const paid = tx?.status === 'APPROVED';
  const waiting = !tx || WAITING.includes(tx.status ?? '');

  const copy = async () => {
    if (!code) return;
    await Clipboard.setStringAsync(code);
    success();
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <Screen
      bottomPad={40}
      onRefresh={async () => {
        try {
          setTx(await api.transaction(id));
          setError(null);
        } catch (e) {
          setError(e instanceof ApiError ? e.message : 'Não foi possível consultar a cobrança.');
        }
      }}
    >
      <View style={st.top}>
        <IconButton icon="close" label="Fechar" onPress={() => router.back()} />
        <Text style={st.topTitle}>Cobrança Pix</Text>
        <View style={{ width: 40 }} />
      </View>

      <View style={{ alignItems: 'center', marginTop: 8 }}>
        <Money cents={amount} forceShow style={st.amount} />
        <View style={{ marginTop: 8 }}>
          <Badge label={info.label} color={info.color} bg={info.bg} />
        </View>
      </View>

      <ErrorBanner message={error} />

      {paid ? (
        <Card style={st.paidCard}>
          <Animated.View style={{ transform: [{ scale: pop.interpolate({ inputRange: [0, 1], outputRange: [0.4, 1] }) }], opacity: pop }}>
            <View style={st.paidIcon}>
              <Ionicons name="checkmark" size={44} color="#000" />
            </View>
          </Animated.View>
          <Text style={st.paidTitle}>Pagamento recebido!</Text>
          <Text style={st.paidSub}>{brl(amount)} já entrou na sua conta.</Text>
          <Button
            title="Compartilhar comprovante"
            icon="share-outline"
            variant="secondary"
            style={{ marginTop: 20, alignSelf: 'stretch' }}
            onPress={() =>
              Share.share({
                message: [
                  `Comprovante de Pix recebido · ${BRAND.name}`,
                  '',
                  `Valor: ${brl(amount)}`,
                  tx?.customer?.name ? `Pagador: ${tx.customer.name}` : null,
                  `Data: ${dateTime(tx?.paid_at ?? tx?.paidAt ?? (tx ? txDate(tx) : null))}`,
                  `Identificador: ${id}`,
                ]
                  .filter(Boolean)
                  .join('\n'),
              })
            }
          />
        </Card>
      ) : code || image ? (
        <Card style={{ alignItems: 'center', marginTop: 18 }}>
          <View style={st.qrWrap}>
            {image ? (
              <Image source={{ uri: image.startsWith('data:') ? image : `data:image/png;base64,${image}` }} style={st.qr} contentFit="contain" />
            ) : (
              <ActivityIndicator color={C.red} />
            )}
          </View>
          {waiting ? (
            <View style={st.waitRow}>
              <ActivityIndicator size="small" color={C.orange} />
              <Text style={st.waitText}>Aguardando o pagamento…</Text>
            </View>
          ) : null}
          {code ? (
            <Text style={st.code} numberOfLines={2} selectable>
              {code}
            </Text>
          ) : null}
          <View style={{ width: '100%', gap: 10, marginTop: 14 }}>
            <Button title={copied ? 'Código copiado!' : 'Copiar Pix copia e cola'} icon={copied ? 'checkmark' : 'copy'} onPress={copy} disabled={!code} />
            <Button
              title="Compartilhar"
              icon="share-outline"
              variant="secondary"
              disabled={!code}
              onPress={() => code && Share.share({ message: `Pix de ${brl(amount)}\n\nCopia e cola:\n${code}` })}
            />
          </View>
        </Card>
      ) : !tx && !error ? (
        <ActivityIndicator color={C.red} style={{ marginTop: 40 }} />
      ) : null}

      {tx?.status === 'REFUSED' && tx.refused_reason ? <ErrorBanner message={tx.refused_reason} /> : null}

      <Card style={{ marginTop: 16, paddingVertical: 4 }}>
        <KV k="Criada em" v={dateTime(tx ? txDate(tx) : null)} />
        {tx?.paid_at || tx?.paidAt ? <KV k="Paga em" v={dateTime(tx.paid_at ?? tx.paidAt)} /> : null}
        {tx?.customer?.name ? <KV k="Pagador" v={tx.customer.name} /> : null}
        {tx?.description ? <KV k="Descrição" v={tx.description} /> : null}
        <KV k="Identificador" v={id.slice(0, 8).toUpperCase()} last />
      </Card>
    </Screen>
  );
}

const st = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12, marginTop: 6 },
  topTitle: { color: C.text, fontSize: 17, fontWeight: '700' },
  amount: { fontSize: 44, fontWeight: '800', letterSpacing: -1.2 },
  qrWrap: { backgroundColor: '#fff', padding: 14, borderRadius: 22, width: 244, height: 244, alignItems: 'center', justifyContent: 'center' },
  qr: { width: 216, height: 216 },
  waitRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 16 },
  waitText: { color: C.orange, fontSize: 14, fontWeight: '600' },
  code: { color: C.muted, fontSize: 12, marginTop: 12, textAlign: 'center', fontFamily: 'Menlo' },
  paidCard: { alignItems: 'center', marginTop: 18, paddingVertical: 32 },
  paidIcon: { width: 84, height: 84, borderRadius: 42, backgroundColor: C.green, alignItems: 'center', justifyContent: 'center' },
  paidTitle: { color: C.text, fontSize: 22, fontWeight: '800', marginTop: 18 },
  paidSub: { color: C.muted, fontSize: 15, marginTop: 6 },
});
