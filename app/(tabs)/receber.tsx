import React, { useCallback, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { router, useFocusEffect } from 'expo-router';
import * as Crypto from 'expo-crypto';
import { Button, ErrorBanner, Header, Keypad, Screen, failure, success } from '../../src/components/ui';
import { api, ApiError } from '../../src/lib/api';
import { brl, feeText } from '../../src/lib/format';
import { chargeCache } from '../../src/lib/cache';
import { useSession } from '../../src/session';
import { C } from '../../src/theme';

export default function Receber() {
  const { company, refreshCompany } = useSession();
  const [amount, setAmount] = useState(0);
  const [limits, setLimits] = useState<{ min: number; max: number | null }>({ min: 100, max: null });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Recebimento via Pix desligado (pela taxa da conta ou pelo aviso do servidor): avisa antes de digitar
  const [blocked, setBlocked] = useState<string | null>(null);

  const pixFee = company?.fees?.incoming?.pix;
  const pixOff = pixFee?.available === false ? 'O recebimento via Pix não está liberado para a sua conta.' : null;
  const notice = pixOff || blocked;

  // Limites de valor configurados no painel (mínimo e máximo por Pix)
  const loadLimits = useCallback(
    () =>
      api
        .depositLimits()
        .then((l) => setLimits({ min: l.min_deposit_amount ?? 100, max: l.max_deposit_amount ?? null }))
        .catch(() => {}),
    []
  );
  useFocusEffect(
    useCallback(() => {
      loadLimits();
    }, [loadLimits])
  );

  const tooLow = amount > 0 && amount < limits.min;
  const tooHigh = limits.max !== null && amount > limits.max;

  const create = async () => {
    setError(null);
    setLoading(true);
    try {
      // Mesma rota do painel: gera o Pix e devolve o QR Code
      const d = await api.deposit(amount, Crypto.randomUUID());
      chargeCache.set(d.transactionId, { code: d.qrcode, image: d.qrcodeBase64, amount: d.amount });
      success();
      setAmount(0);
      router.push(`/cobranca/${d.transactionId}`);
    } catch (e) {
      failure();
      const msg = e instanceof ApiError ? e.message : 'Não foi possível gerar o Pix.';
      if (/desativad|indispon|suspens/i.test(msg)) setBlocked(msg);
      else setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Screen
      bottomPad={130}
      onRefresh={() => {
        setBlocked(null); // puxar pra atualizar tenta de novo
        return Promise.all([loadLimits(), refreshCompany()]);
      }}
    >
      <Header title="Receber" subtitle="Gere um QR Code Pix na hora" />

      {notice ? (
        <View style={st.notice}>
          <Ionicons name="pause-circle" size={22} color={C.orange} />
          <View style={{ flex: 1, marginLeft: 10 }}>
            <Text style={st.noticeTitle}>Recebimentos pausados</Text>
            <Text style={st.noticeText}>{notice} Puxe a tela pra baixo pra tentar de novo.</Text>
          </View>
        </View>
      ) : null}

      <View style={st.amountBox}>
        <Text style={st.amountLabel}>Quanto você quer receber?</Text>
        <Text style={[st.amount, amount === 0 && { color: C.dim }]} adjustsFontSizeToFit numberOfLines={1}>
          {brl(amount)}
        </Text>
        {pixFee?.available !== false && (pixFee?.fixed || pixFee?.percentage) ? (
          <Text style={st.feeHint}>Taxa de recebimento Pix: {feeText(pixFee?.fixed, pixFee?.percentage)}</Text>
        ) : null}
      </View>

      <Keypad value={amount} onChange={setAmount} />

      <ErrorBanner message={error} />

      <Button
        title="Gerar QR Code Pix"
        icon="qr-code"
        onPress={create}
        loading={loading}
        disabled={amount <= 0 || tooLow || tooHigh || !!notice}
        style={{ marginTop: 16 }}
      />
      {tooLow ? <Text style={st.hint}>O valor mínimo é {brl(limits.min)}</Text> : null}
      {tooHigh && limits.max !== null ? <Text style={st.hint}>O valor máximo é {brl(limits.max)}</Text> : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  amountBox: { alignItems: 'center', paddingVertical: 18 },
  amountLabel: { color: C.muted, fontSize: 15, fontWeight: '600' },
  amount: { color: C.text, fontSize: 52, fontWeight: '800', letterSpacing: -1.5, marginTop: 6, fontVariant: ['tabular-nums'] },
  feeHint: { color: C.muted, fontSize: 13, marginTop: 6 },
  notice: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    backgroundColor: 'rgba(255,159,10,0.1)',
    borderColor: 'rgba(255,159,10,0.35)',
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: 16,
    padding: 14,
    marginTop: 4,
  },
  noticeTitle: { color: C.text, fontSize: 15, fontWeight: '700' },
  noticeText: { color: C.sub, fontSize: 13.5, marginTop: 2, lineHeight: 19 },
  hint: { color: C.muted, textAlign: 'center', marginTop: 8, fontSize: 13 },
});
