import React, { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Share, StyleSheet, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { Badge, Button, Card, ErrorBanner, IconButton, KV, Money, Screen, failure, success, wdStatus } from '../../src/components/ui';
import { api, ApiError, Withdraw } from '../../src/lib/api';
import { brl, dateTime, txDate } from '../../src/lib/format';
import { displayKey, keyLabel } from '../../src/lib/pix';
import { C } from '../../src/theme';
import { BRAND, A } from '../../src/brand';

const ACTIVE = ['PENDING_ANALYSIS', 'APPROVED'];

const STEPS = [
  { key: 'created', label: 'Solicitado' },
  { key: 'analysis', label: 'Em análise' },
  { key: 'sent', label: 'Enviado ao banco' },
  { key: 'done', label: 'Concluído' },
];

function progress(w: Withdraw | null) {
  switch (w?.status) {
    case 'PENDING_ANALYSIS':
      return 1;
    case 'APPROVED':
      return 2;
    case 'COMPLETED':
      return 3;
    default:
      return 0;
  }
}

export default function Saque() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const [w, setW] = useState<Withdraw | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);

  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    let prev: string | undefined;
    const tick = async () => {
      try {
        const r = await api.withdrawById(id);
        if (!alive) return;
        if (prev && prev !== r.status) {
          if (r.status === 'COMPLETED') success();
          if (r.status === 'REFUSED') failure();
        }
        prev = r.status;
        setW(r);
        setError(null);
        if (ACTIVE.includes(r.status)) timer = setTimeout(tick, 4000);
      } catch (e) {
        if (!alive) return;
        setError(e instanceof ApiError ? e.message : 'Não foi possível consultar o envio.');
        timer = setTimeout(tick, 8000);
      }
    };
    tick();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [id]);

  const cancel = () =>
    Alert.alert('Cancelar envio?', 'O valor volta pro seu saldo disponível.', [
      { text: 'Voltar', style: 'cancel' },
      {
        text: 'Cancelar envio',
        style: 'destructive',
        onPress: async () => {
          setCancelling(true);
          try {
            setW(await api.cancelWithdraw(id));
            success();
          } catch (e) {
            failure();
            setError(e instanceof ApiError ? e.message : 'Não foi possível cancelar.');
          } finally {
            setCancelling(false);
          }
        },
      },
    ]);

  const info = wdStatus(w?.status);
  const failed = w?.status === 'REFUSED' || w?.status === 'CANCELLED';
  const p = progress(w);
  const reason = w?.reason ?? w?.failure_history?.[w.failure_history.length - 1]?.reason ?? null;
  const isQr = w?.destination_type === 'QR_CODE';
  const dest = isQr ? w?.description || 'QR Code Pix' : displayKey(w?.pix_key ?? '', w?.pix_key_type ?? null);

  return (
    <Screen bottomPad={40}>
      <View style={st.top}>
        <IconButton icon="close" label="Fechar" onPress={() => router.back()} />
        <Text style={st.topTitle}>{isQr ? 'Pagamento QR Code' : 'Pix enviado'}</Text>
        <View style={{ width: 40 }} />
      </View>

      {!w && !error ? <ActivityIndicator color={C.red} style={{ marginTop: 60 }} /> : null}
      <ErrorBanner message={error} />

      {w ? (
        <>
          <View style={{ alignItems: 'center', marginTop: 10 }}>
            <View style={[st.bigIcon, { backgroundColor: info.bg }]}>
              <Ionicons name={w.status === 'COMPLETED' ? 'checkmark' : info.icon} size={38} color={info.color} />
            </View>
            <Money cents={w.amount} forceShow style={[st.amount, failed && { color: C.muted, textDecorationLine: 'line-through' }]} />
            <Text style={st.dest} numberOfLines={1}>
              para {dest}
            </Text>
            <View style={{ marginTop: 10 }}>
              <Badge label={info.label} color={info.color} bg={info.bg} />
            </View>
          </View>

          {failed ? (
            <View style={st.failBox}>
              <Ionicons name="warning" size={18} color={C.redLight} />
              <Text style={st.failText}>
                {w.status === 'REFUSED' ? 'O envio foi recusado. O valor voltou pro seu saldo.' : 'O envio foi cancelado. O valor voltou pro seu saldo.'}
                {reason ? `\nMotivo: ${reason}` : ''}
              </Text>
            </View>
          ) : (
            <Card style={{ marginTop: 22 }}>
              {STEPS.map((sItem, i) => {
                const done = i <= p;
                const current = i === p && w.status !== 'COMPLETED';
                return (
                  <View key={sItem.key} style={st.stepRow}>
                    <View style={{ alignItems: 'center', width: 24 }}>
                      <View style={[st.dot, done && { backgroundColor: C.red, borderColor: C.red }]}>
                        {done && !current ? <Ionicons name="checkmark" size={12} color="#fff" /> : null}
                        {current ? <ActivityIndicator size="small" color="#fff" style={{ transform: [{ scale: 0.6 }] }} /> : null}
                      </View>
                      {i < STEPS.length - 1 ? <View style={[st.line, i < p && { backgroundColor: C.red }]} /> : null}
                    </View>
                    <Text style={[st.stepText, done && { color: C.text }]}>{sItem.label}</Text>
                  </View>
                );
              })}
            </Card>
          )}

          <Card style={{ marginTop: 16, paddingVertical: 4 }}>
            {!isQr && w.pix_key_type ? <KV k={`Chave (${keyLabel(w.pix_key_type)})`} v={dest} /> : null}
            {isQr && w.description ? <KV k="Recebedor" v={w.description} /> : null}
            <KV k="Valor debitado" v={brl(w.amount)} />
            <KV k="Taxa" v={brl(w.fee)} color={C.redLight} />
            <KV k="Destinatário recebe" v={brl(w.net_amount)} bold />
            <KV k="Data" v={dateTime(txDate(w))} />
            {!isQr && w.description ? <KV k="Descrição" v={w.description} /> : null}
            <KV k="Identificador" v={w.id.slice(0, 8).toUpperCase()} last />
          </Card>

          <View style={{ gap: 10, marginTop: 18 }}>
            {w.status === 'COMPLETED' ? (
              <Button
                title="Compartilhar comprovante"
                icon="share-outline"
                variant="secondary"
                onPress={() =>
                  Share.share({
                    message:
                      `Comprovante ${BRAND.name}\n\n` +
                      `Pix de ${brl(w.net_amount)}\nPara: ${dest}\nData: ${dateTime(txDate(w))}\nID: ${w.id}${w.external_id ? `\nE2E: ${w.external_id}` : ''}`,
                  })
                }
              />
            ) : null}
            {w.status === 'PENDING_ANALYSIS' ? (
              <Button title="Cancelar envio" variant="danger" onPress={cancel} loading={cancelling} />
            ) : null}
            <Button title="Fechar" variant="plain" onPress={() => router.back()} />
          </View>
        </>
      ) : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12, marginTop: 6 },
  topTitle: { color: C.text, fontSize: 17, fontWeight: '700' },
  bigIcon: { width: 76, height: 76, borderRadius: 38, alignItems: 'center', justifyContent: 'center', marginBottom: 14 },
  amount: { fontSize: 42, fontWeight: '800', letterSpacing: -1.2 },
  dest: { color: C.muted, fontSize: 15, marginTop: 4, maxWidth: '90%' },
  failBox: {
    flexDirection: 'row',
    gap: 10,
    backgroundColor: C.redSoft,
    borderRadius: 16,
    padding: 16,
    marginTop: 22,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: A(0.4),
  },
  failText: { color: C.text, flex: 1, fontSize: 14, lineHeight: 20 },
  stepRow: { flexDirection: 'row', gap: 12, minHeight: 44 },
  dot: { width: 22, height: 22, borderRadius: 11, borderWidth: 2, borderColor: C.card3, alignItems: 'center', justifyContent: 'center' },
  line: { width: 2, flex: 1, backgroundColor: C.card3, marginVertical: 2 },
  stepText: { color: C.dim, fontSize: 15, fontWeight: '600', paddingTop: 1 },
});
