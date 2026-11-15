import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import * as Clipboard from 'expo-clipboard';
import * as Crypto from 'expo-crypto';
import { Ionicons } from '@expo/vector-icons';
import {
  Button,
  Card,
  ErrorBanner,
  Header,
  IconButton,
  KV,
  Keypad,
  Money,
  Row,
  Screen,
  Steps,
  failure,
  success,
  tap,
  wdStatus,
} from '../../src/components/ui';
import { api, ApiError, calcFee, PixKeyType, Withdraw, WithdrawRules } from '../../src/lib/api';
import { brl, dayLabel, timeLabel, txDate } from '../../src/lib/format';
import { detectKeyType, displayKey, isBrCode, isValidKey, KEY_TYPES, keyLabel, normalizeKey } from '../../src/lib/pix';
import { useSession } from '../../src/session';
import { forgetKey, loadRecentKeys, RecentKey, rememberKey } from '../../src/lib/recentKeys';
import { C } from '../../src/theme';
import { A } from '../../src/brand';
import { BIO_ICON } from '../../src/brand';

type Step = 0 | 1 | 2;

const KEY_ICON: Record<string, keyof typeof Ionicons.glyphMap> = {
  CPF: 'person-outline',
  CNPJ: 'business-outline',
  EMAIL: 'mail-outline',
  PHONE: 'phone-portrait-outline',
  EVP: 'key-outline',
};

export default function Enviar() {
  const { confirmIdentity, biometricLabel } = useSession();
  const [step, setStep] = useState<Step>(0);
  const [key, setKey] = useState('');
  const [type, setType] = useState<PixKeyType | null>(null);
  const [manualType, setManualType] = useState(false);
  const [amount, setAmount] = useState(0);
  const [desc, setDesc] = useState('');
  const [available, setAvailable] = useState<number | null>(null);
  const [feeCfg, setFeeCfg] = useState<{ fixed: number; percent: number } | null>(null);
  const [idem, setIdem] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recent, setRecent] = useState<RecentKey[]>([]);
  // Mínimo/máximo por envio definidos no painel (Configurações → Saques)
  const [rules, setRules] = useState<WithdrawRules | null>(null);
  const [lastWd, setLastWd] = useState<Withdraw[]>([]);

  // Saldo e configuração de taxa (uma consulta só; o resto é calculado localmente)
  const reload = useCallback(
    (withFee: boolean) =>
      Promise.all([
        api.balance().then((b) => setAvailable(b.available_balance)).catch(() => {}),
        loadRecentKeys().then(setRecent).catch(() => {}),
        api.withdrawRules().then((r) => r && setRules(r)).catch(() => {}),
        api.withdraws({ perPage: 5 }).then((p) => setLastWd(p.items)).catch(() => {}),
        withFee
          ? api
              .feePreview(10000)
              .then((f) => setFeeCfg({ fixed: f.fixed_fee, percent: f.percent_fee }))
              .catch(() => {})
          : null,
      ]),
    []
  );
  useFocusEffect(
    useCallback(() => {
      reload(!feeCfg);
    }, [feeCfg, reload])
  );
  // Puxar pra baixo: atualiza saldo, chaves recentes e taxa
  const onRefresh = () => reload(true);

  useEffect(() => {
    if (!manualType) setType(detectKeyType(key));
  }, [key, manualType]);

  const fee = feeCfg && amount > 0 ? calcFee(amount, feeCfg.fixed, feeCfg.percent) : 0;
  const net = Math.max(amount - fee, 0);
  const keyOk = isValidKey(key, type);
  const over = available !== null && amount > available;
  const minAmt = Math.max(rules?.min ?? 100, 1);
  const maxAmt = rules?.max ?? null;
  const belowMin = amount > 0 && amount < minAmt;
  const aboveMax = maxAmt !== null && amount > maxAmt;
  const limitText = maxAmt !== null ? `Mínimo ${brl(minAmt)} · Máximo ${brl(maxAmt)} por envio` : `Mínimo ${brl(minAmt)} por envio`;

  const reset = () => {
    setStep(0);
    setKey('');
    setType(null);
    setManualType(false);
    setAmount(0);
    setDesc('');
    setError(null);
  };

  const [hasCopied, setHasCopied] = useState(false);
  // Só verifica SE há texto copiado (não lê o conteúdo, então o iPhone não pergunta nada)
  useFocusEffect(
    useCallback(() => {
      Clipboard.hasStringAsync().then(setHasCopied).catch(() => {});
    }, [])
  );

  const applyPasted = (raw: string) => {
    const txt = (raw ?? '').trim();
    if (!txt) return;
    tap();
    setHasCopied(false);
    if (isBrCode(txt)) {
      router.push({ pathname: '/pagar-qr', params: { code: txt } });
      return;
    }
    setManualType(false);
    setKey(txt);
  };

  const paste = async () => {
    tap();
    const txt = (await Clipboard.getStringAsync()).trim();
    if (!txt) return;
    if (isBrCode(txt)) {
      router.push({ pathname: '/pagar-qr', params: { code: txt } });
      return;
    }
    setManualType(false);
    setKey(txt);
  };

  const goConfirm = () => {
    setIdem(Crypto.randomUUID()); // uma chave por envio: evita pagar duas vezes
    setError(null);
    setStep(2);
  };

  const send = async () => {
    if (!type) return;
    setError(null);
    const ok = await confirmIdentity(`Confirmar envio de ${brl(amount)}`);
    if (!ok) return;
    setSending(true);
    try {
      const w = await api.withdraw({ pixKeyType: type, pixKey: normalizeKey(key, type), amount, description: desc.trim() || undefined }, idem);
      success();
      rememberKey(normalizeKey(key, type), type).catch(() => {});
      reset();
      router.push(`/saque/${w.id}`);
    } catch (e) {
      failure();
      setError(e instanceof ApiError ? e.message : 'Não foi possível enviar o Pix.');
    } finally {
      setSending(false);
    }
  };

  // ---------- Passo 1: chave ----------
  if (step === 0)
    return (
      <Screen onRefresh={onRefresh}>
        <Header title="Enviar Pix" subtitle="Pra quem você quer enviar?" />
        <Steps total={3} current={0} />

        <View style={[st.keyField, key.length > 3 && !keyOk && { borderColor: A(0.6) }]}>
          <TextInput
            value={key}
            onChangeText={(v) => setKey(v)}
            placeholder="CPF, CNPJ, celular, e-mail ou chave aleatória"
            placeholderTextColor={C.dim}
            autoCapitalize="none"
            autoCorrect={false}
            style={st.keyInput}
            returnKeyType="next"
            onSubmitEditing={() => keyOk && setStep(1)}
          />
          {key ? (
            <Pressable hitSlop={10} onPress={() => setKey('')}>
              <Ionicons name="close-circle" size={20} color={C.dim} />
            </Pressable>
          ) : null}
        </View>

        {hasCopied && key.trim().length === 0 && Clipboard.isPasteButtonAvailable ? (
          <View style={st.copiedCard}>
            <Ionicons name="clipboard" size={20} color={C.redLight} />
            <Text style={st.copiedText}>Você tem algo copiado. Chave Pix ou Pix copia e cola? Cole aqui:</Text>
          </View>
        ) : null}

        <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
          {Clipboard.isPasteButtonAvailable ? (
            // Botão nativo da Apple: cola sem a pergunta "Permitir colar?"
            <View style={{ flex: 1, height: 50, borderRadius: 16, overflow: 'hidden' }}>
              <Clipboard.ClipboardPasteButton
                onPress={(d: any) => applyPasted(d?.type === 'text' ? d.text : '')}
                acceptedContentTypes={['plain-text']}
                displayMode="iconAndLabel"
                cornerStyle="large"
                backgroundColor="#1C1C21"
                foregroundColor="#FFFFFF"
                style={{ width: '100%', height: 50 }}
              />
            </View>
          ) : (
            <Button title="Colar" icon="clipboard-outline" variant="secondary" style={{ flex: 1, height: 50 }} onPress={paste} />
          )}
          <Button title="Ler QR Code" icon="scan" variant="secondary" style={{ flex: 1, height: 50 }} onPress={() => router.push('/pagar-qr')} />
        </View>

        {recent.length > 0 && key.trim().length === 0 ? (
          <>
            <Text style={st.label}>Chaves recentes</Text>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 10, paddingRight: 20 }} style={{ marginHorizontal: -20, paddingLeft: 20 }}>
              {recent.map((k) => (
                <Pressable
                  key={k.key}
                  onPress={() => {
                    tap();
                    setManualType(true);
                    setType(k.type);
                    setKey(displayKey(k.key, k.type));
                    setStep(1);
                  }}
                  onLongPress={() =>
                    Alert.alert('Remover chave?', displayKey(k.key, k.type), [
                      { text: 'Cancelar', style: 'cancel' },
                      {
                        text: 'Remover',
                        style: 'destructive',
                        onPress: async () => {
                          await forgetKey(k.key);
                          setRecent((list) => list.filter((x) => x.key !== k.key));
                        },
                      },
                    ])
                  }
                  style={({ pressed }) => [st.recent, { opacity: pressed ? 0.7 : 1 }]}
                >
                  <View style={st.recentIcon}>
                    <Ionicons name={KEY_ICON[k.type] ?? 'key-outline'} size={20} color={C.redLight} />
                  </View>
                  <Text style={st.recentKey} numberOfLines={1}>
                    {displayKey(k.key, k.type)}
                  </Text>
                  <Text style={st.recentType}>{keyLabel(k.type)}</Text>
                </Pressable>
              ))}
            </ScrollView>
          </>
        ) : null}

        {key.trim().length > 0 ? (
          <>
            <Text style={st.label}>Tipo de chave</Text>
            <View style={st.chips}>
              {KEY_TYPES.map((k) => (
                <Pressable
                  key={k.type}
                  onPress={() => {
                    tap();
                    setManualType(true);
                    setType(k.type);
                  }}
                  style={[st.chip, type === k.type && st.chipOn]}
                >
                  <Text style={[st.chipText, type === k.type && { color: C.text }]}>{k.label}</Text>
                </Pressable>
              ))}
            </View>
            {!keyOk && type ? <Text style={st.hint}>Essa chave não parece um {keyLabel(type)} válido.</Text> : null}
          </>
        ) : (
          <Card style={{ marginTop: 22 }}>
            <View style={{ flexDirection: 'row', gap: 12 }}>
              <Ionicons name="flash" size={20} color={C.redLight} />
              <Text style={{ color: C.sub, flex: 1, lineHeight: 20 }}>
                Pix cai na hora, 24h por dia. Você confirma o envio com {biometricLabel} antes de qualquer dinheiro sair.
              </Text>
            </View>
          </Card>
        )}

        <Button title="Continuar" onPress={() => setStep(1)} disabled={!keyOk} style={{ marginTop: 22 }} />

        {key.trim().length === 0 && lastWd.length > 0 ? (
          <>
            <Text style={st.label}>Últimas transferências</Text>
            <Card style={{ paddingVertical: 4 }}>
              {lastWd.map((w, i) => {
                const info = wdStatus(w.status);
                const failed = w.status === 'REFUSED' || w.status === 'CANCELLED';
                const d = txDate(w);
                return (
                  <Row
                    key={w.id}
                    icon={failed ? info.icon : 'arrow-up'}
                    iconColor={failed ? info.color : C.redLight}
                    iconBg={failed ? info.bg : C.redSoft}
                    title={w.destination_type === 'QR_CODE' ? w.description || 'Pagamento QR Code' : displayKey(w.pix_key ?? 'Pix', w.pix_key_type ?? null)}
                    subtitle={`${info.label} · ${dayLabel(d)}, ${timeLabel(d)}`}
                    right={
                      <Money
                        cents={w.amount}
                        sign="-"
                        style={{ fontSize: 15, fontWeight: '700', color: failed ? C.muted : C.text, textDecorationLine: failed ? 'line-through' : 'none' }}
                      />
                    }
                    onPress={() => router.push(`/saque/${w.id}`)}
                    last={i === lastWd.length - 1}
                  />
                );
              })}
            </Card>
          </>
        ) : null}

      </Screen>
    );

  // ---------- Passo 2: valor ----------
  if (step === 1)
    return (
      <Screen onRefresh={onRefresh}>
        <View style={st.navRow}>
          <IconButton icon="chevron-back" label="Voltar" onPress={() => setStep(0)} />
          <Text style={st.navTitle}>Qual o valor?</Text>
          <View style={{ width: 40 }} />
        </View>
        <Steps total={3} current={1} />

        <Card style={st.recipient}>
          <View style={st.avatar}>
            <Ionicons name="person" size={20} color={C.text} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={st.recLabel}>{keyLabel(type)}</Text>
            <Text style={st.recKey} numberOfLines={1}>
              {displayKey(key, type)}
            </Text>
          </View>
          <Pressable hitSlop={10} onPress={() => setStep(0)}>
            <Text style={st.change}>Alterar</Text>
          </Pressable>
        </Card>

        <View style={{ alignItems: 'center', marginTop: 18 }}>
          <Text style={st.amountLabel}>Valor a enviar</Text>
          <Text style={[st.amount, amount === 0 && { color: C.dim }, over && { color: C.redLight }]} adjustsFontSizeToFit numberOfLines={1}>
            {brl(amount)}
          </Text>
          <Text style={[st.avail, over && { color: C.redLight }]}>
            {over ? 'Saldo insuficiente · ' : ''}Disponível: <Money cents={available} style={[st.avail, over && { color: C.redLight }]} />
          </Text>
          <Text style={[st.limits, (belowMin || aboveMax) && { color: C.redLight }]}>
            {aboveMax ? `Acima do máximo de ${brl(maxAmt!)} por envio` : belowMin ? `Abaixo do mínimo de ${brl(minAmt)} por envio` : limitText}
          </Text>
        </View>

        <View style={st.feeRow}>
          <View>
            <Text style={st.feeLabel}>Taxa</Text>
            <Text style={st.feeValue}>{brl(fee)}</Text>
          </View>
          <View style={{ alignItems: 'flex-end' }}>
            <Text style={st.feeLabel}>Destinatário recebe</Text>
            <Text style={[st.feeValue, { color: C.green }]}>{brl(net)}</Text>
          </View>
        </View>

        <Keypad value={amount} onChange={setAmount} />

        <Button title="Revisar envio" onPress={goConfirm} disabled={amount < minAmt || aboveMax || over || net <= 0} style={{ marginTop: 12 }} />
      </Screen>
    );

  // ---------- Passo 3: confirmação ----------
  return (
    <Screen onRefresh={onRefresh}>
      <View style={st.navRow}>
        <IconButton icon="chevron-back" label="Voltar" onPress={() => setStep(1)} />
        <Text style={st.navTitle}>Confirme o envio</Text>
        <View style={{ width: 40 }} />
      </View>
      <Steps total={3} current={2} />

      <View style={{ alignItems: 'center', marginVertical: 22 }}>
        <Text style={st.bigAmount} adjustsFontSizeToFit numberOfLines={1}>
          {brl(amount)}
        </Text>
        <Text style={st.amountLabel}>via Pix para {displayKey(key, type)}</Text>
      </View>

      <Card style={{ paddingVertical: 4 }}>
        <KV k={`Chave (${keyLabel(type)})`} v={displayKey(key, type)} />
        <KV k="Valor" v={brl(amount)} />
        <KV k="Taxa" v={`- ${brl(fee)}`} color={C.redLight} />
        <KV k="Destinatário recebe" v={brl(net)} bold />
        <KV k="Saldo após envio" v={available !== null ? brl(available - amount) : '—'} color={C.green} last />
      </Card>

      <View style={[st.keyField, { marginTop: 14, height: 52 }]}>
        <Ionicons name="text" size={17} color={C.muted} />
        <TextInput
          value={desc}
          onChangeText={setDesc}
          placeholder="Descrição (opcional)"
          placeholderTextColor={C.dim}
          maxLength={255}
          style={[st.keyInput, { fontSize: 15 }]}
        />
      </View>

      <View style={st.warn}>
        <Ionicons name="alert-circle" size={18} color={C.orange} />
        <Text style={st.warnText}>Confira a chave antes de enviar. Um Pix enviado não pode ser desfeito.</Text>
      </View>

      <ErrorBanner message={error} />

      <Button title={`Enviar com ${biometricLabel}`} icon={BIO_ICON} onPress={send} loading={sending} style={{ marginTop: 8 }} />
      <Button title="Cancelar" variant="plain" onPress={reset} style={{ marginTop: 4 }} />
    </Screen>
  );
}

const st = StyleSheet.create({
  keyField: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    backgroundColor: C.card,
    borderRadius: 18,
    paddingHorizontal: 16,
    height: 60,
    marginTop: 22,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: C.borderStrong,
  },
  keyInput: { flex: 1, color: C.text, fontSize: 17 },
  label: { color: C.sub, fontSize: 14, fontWeight: '600', marginTop: 22, marginBottom: 10 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { paddingHorizontal: 14, paddingVertical: 9, borderRadius: 12, backgroundColor: C.card, borderWidth: StyleSheet.hairlineWidth, borderColor: C.border },
  chipOn: { backgroundColor: C.redSoft, borderColor: C.red },
  chipText: { color: C.muted, fontSize: 14, fontWeight: '600' },
  hint: { color: C.redLight, fontSize: 13, marginTop: 10 },
  recent: {
    width: 116,
    alignItems: 'center',
    backgroundColor: C.card,
    borderRadius: 18,
    paddingVertical: 14,
    paddingHorizontal: 10,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: C.border,
  },
  recentIcon: { width: 46, height: 46, borderRadius: 23, backgroundColor: C.redSoft, alignItems: 'center', justifyContent: 'center', marginBottom: 10 },
  recentKey: { color: C.text, fontSize: 13, fontWeight: '700', maxWidth: 96 },
  recentType: { color: C.muted, fontSize: 12, marginTop: 3 },
  navRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14, marginTop: 6 },
  navTitle: { color: C.text, fontSize: 18, fontWeight: '700' },
  recipient: { flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 18, padding: 14 },
  avatar: { width: 44, height: 44, borderRadius: 22, backgroundColor: C.redSoft, alignItems: 'center', justifyContent: 'center' },
  recLabel: { color: C.muted, fontSize: 13 },
  recKey: { color: C.text, fontSize: 16, fontWeight: '700', marginTop: 2 },
  change: { color: C.redLight, fontSize: 15, fontWeight: '700' },
  amountLabel: { color: C.muted, fontSize: 15, fontWeight: '600', textAlign: 'center' },
  amount: { color: C.text, fontSize: 50, fontWeight: '800', letterSpacing: -1.5, marginTop: 4, fontVariant: ['tabular-nums'] },
  copiedCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginTop: 12,
    padding: 12,
    borderRadius: 14,
    backgroundColor: A(0.08),
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: A(0.35),
  },
  copiedText: { color: C.sub, fontSize: 13.5, flex: 1, lineHeight: 18 },
  limits: { color: C.muted, fontSize: 13, marginTop: 6, textAlign: 'center' },
  avail: { color: C.muted, fontSize: 14, marginTop: 4 },
  feeRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    backgroundColor: C.card,
    borderRadius: 16,
    padding: 14,
    marginTop: 16,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: C.border,
  },
  feeLabel: { color: C.muted, fontSize: 12 },
  feeValue: { color: C.text, fontSize: 16, fontWeight: '700', marginTop: 2 },
  bigAmount: { color: C.text, fontSize: 54, fontWeight: '800', letterSpacing: -1.6, marginBottom: 4 },
  warn: { flexDirection: 'row', gap: 10, alignItems: 'center', marginTop: 16, paddingHorizontal: 4 },
  warnText: { color: C.sub, fontSize: 13, flex: 1, lineHeight: 18 },
});
