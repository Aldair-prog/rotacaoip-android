import React, { useEffect, useState } from 'react';
import { Linking, StyleSheet, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { CameraView, useCameraPermissions } from 'expo-camera';
import * as Clipboard from 'expo-clipboard';
import * as Crypto from 'expo-crypto';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { Button, Card, ErrorBanner, IconButton, KV, Keypad, Screen, failure, success } from '../src/components/ui';
import { api, ApiError, DecodedQr, PixPayment } from '../src/lib/api';
import { brl } from '../src/lib/format';
import { isBrCode, parseBrCode } from '../src/lib/pix';
import { useSession } from '../src/session';
import { C, FILL } from '../src/theme';
import { SETTINGS_PATH, BIO_ICON } from '../src/brand';

// Mensagem clara quando o pagamento por QR estiver desativado no painel admin
function unavailableMsg(e: ApiError) {
  if (e.status === 403) return 'Pagamento por QR Code indisponível no momento.';
  return e.message;
}

export default function PagarQr() {
  const params = useLocalSearchParams<{ code?: string }>();
  const ins = useSafeAreaInsets();
  const { confirmIdentity, biometricLabel } = useSession();
  const [perm, requestPerm] = useCameraPermissions();
  const [code, setCode] = useState<string | null>(params.code && isBrCode(params.code) ? params.code : null);
  const [amount, setAmount] = useState(0);
  const [feeCfg, setFeeCfg] = useState<{ fixed: number; percent: number } | null>(null);
  const [available, setAvailable] = useState<number | null>(null);
  const [idem] = useState(() => Crypto.randomUUID());
  const [sending, setSending] = useState(false);
  const [decoded, setDecoded] = useState<DecodedQr | null>(null);
  const [decoding, setDecoding] = useState(false);
  const [paid, setPaid] = useState<PixPayment | null>(null);
  const [error, setError] = useState<string | null>(null);

  const local = code ? parseBrCode(code) : null;
  const fixedAmount = decoded?.amount ?? local?.amount ?? null;
  const value = fixedAmount ?? amount;
  // Taxa: a que o servidor informou ao ler o QR; senão a configuração de saque
  const fee =
    decoded?.fee ?? (feeCfg && value > 0 ? feeCfg.fixed + Math.floor((value * feeCfg.percent) / 10000) : 0);
  const total = decoded?.totalAmount ?? value + fee; // no QR Code a taxa vai por cima
  const receiver = decoded?.receiverName || local?.merchant || null;
  const city = decoded?.receiverCity || local?.city || null;

  useEffect(() => {
    if (!code) return;
    // Lê o QR no servidor (igual o painel): nome do recebedor, valor e taxa
    setDecoding(true);
    api
      .decodeQr(code)
      .then(setDecoded)
      .catch((e) => {
        if (e instanceof ApiError && (e.status === 403 || e.status === 409)) setError(unavailableMsg(e));
      })
      .finally(() => setDecoding(false));
    api.balance().then((b) => setAvailable(b.available_balance)).catch(() => {});
    api
      .feePreview(10000)
      .then((f) => setFeeCfg({ fixed: f.fixed_fee, percent: f.percent_fee }))
      .catch(() => {});
  }, [code]);

  // Pede a câmera assim que a tela abre (só se ainda não foi perguntado)
  useEffect(() => {
    if (!code && perm && !perm.granted && perm.canAskAgain) requestPerm().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [perm?.status, code]);

  const onScan = ({ data }: { data: string }) => {
    if (code) return;
    if (isBrCode(data)) {
      success();
      setCode(data.trim());
    } else {
      setError('Esse QR Code não é um Pix.');
    }
  };

  const paste = async () => {
    const txt = (await Clipboard.getStringAsync()).trim();
    if (isBrCode(txt)) {
      success();
      setCode(txt);
      setError(null);
    } else {
      failure();
      setError('A área de transferência não tem um Pix copia e cola.');
    }
  };

  const pay = async () => {
    if (!code) return;
    setError(null);
    const ok = await confirmIdentity(`Confirmar pagamento de ${brl(value)}`);
    if (!ok) return;
    setSending(true);
    try {
      const p = await api.payPix({ qrcode: code, amount: fixedAmount ? undefined : amount }, idem);
      success();
      setPaid(p);
    } catch (e) {
      failure();
      setError(e instanceof ApiError ? unavailableMsg(e) : 'Não foi possível pagar o QR Code.');
    } finally {
      setSending(false);
    }
  };

  // ---------- Pagamento feito ----------
  if (paid)
    return (
      <Screen bottomPad={40}>
        <View style={{ alignItems: 'center', marginTop: 50 }}>
          <View style={st.okIcon}>
            <Ionicons name={paid.status === 'FAILED' || paid.status === 'REFUSED' ? 'close' : 'checkmark'} size={44} color="#000" />
          </View>
          <Text style={st.okTitle}>
            {paid.status === 'COMPLETED' || paid.status === 'PAID' || paid.paid_at ? 'Pagamento enviado!' : 'Pagamento em processamento'}
          </Text>
          <Text style={st.okSub}>{brl(paid.amount)} para {paid.receiver_name || receiver || 'o recebedor'}</Text>
        </View>
        <Card style={{ marginTop: 26, paddingVertical: 4 }}>
          <KV k="Valor" v={brl(paid.amount)} />
          <KV k="Taxa" v={brl(paid.fee)} color={C.redLight} />
          <KV k="Total debitado" v={brl(paid.total_amount)} bold last />
        </Card>
        <Button title="Concluir" onPress={() => router.back()} style={{ marginTop: 22 }} />
      </Screen>
    );

  // ---------- Revisão do pagamento ----------
  if (code)
    return (
      <Screen bottomPad={40}>
        <View style={st.top}>
          <IconButton icon="close" label="Fechar" onPress={() => router.back()} />
          <Text style={st.topTitle}>Pagar Pix</Text>
          <View style={{ width: 40 }} />
        </View>

        <Card style={st.merchant}>
          <View style={st.avatar}>
            <Ionicons name="storefront" size={22} color={C.text} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={st.mName} numberOfLines={2}>
              {decoding ? 'Lendo QR Code…' : receiver || 'Recebedor do QR Code'}
            </Text>
            {city ? <Text style={st.mCity}>{city}</Text> : null}
          </View>
        </Card>

        <View style={{ alignItems: 'center', marginTop: 20 }}>
          <Text style={st.label}>{fixedAmount ? 'Valor do QR Code' : 'Quanto você quer pagar?'}</Text>
          <Text style={[st.amount, value === 0 && { color: C.dim }]} adjustsFontSizeToFit numberOfLines={1}>
            {brl(value)}
          </Text>
        </View>

        {!fixedAmount ? <Keypad value={amount} onChange={setAmount} /> : null}

        <Card style={{ marginTop: 16, paddingVertical: 4 }}>
          <KV k="Recebedor recebe" v={brl(value)} />
          <KV k="Taxa" v={`+ ${brl(fee)}`} color={C.redLight} />
          <KV k="Sai do seu saldo" v={brl(total)} bold />
          <KV k="Saldo após pagamento" v={available !== null ? brl(available - total) : '—'} color={C.green} last />
        </Card>

        <ErrorBanner message={error} />

        <Button
          title={`Pagar com ${biometricLabel}`}
          icon={BIO_ICON}
          onPress={pay}
          loading={sending}
          disabled={value < 1 || (available !== null && total > available)}
          style={{ marginTop: 18 }}
        />
        {available !== null && total > available ? <Text style={st.insuf}>Saldo insuficiente</Text> : null}
        <Button title="Ler outro QR Code" variant="plain" onPress={() => setCode(null)} style={{ marginTop: 4 }} />
      </Screen>
    );

  // ---------- Câmera ----------
  return (
    <View style={{ flex: 1, backgroundColor: '#000' }}>
      {perm?.granted ? (
        <CameraView style={FILL} facing="back" barcodeScannerSettings={{ barcodeTypes: ['qr'] }} onBarcodeScanned={onScan} />
      ) : null}

      <View style={[st.overlayTop, { paddingTop: ins.top + 8 }]}>
        <IconButton icon="close" label="Fechar" onPress={() => router.back()} />
        <Text style={st.topTitle}>Ler QR Code Pix</Text>
        <View style={{ width: 40 }} />
      </View>

      <View style={st.frameWrap} pointerEvents="none">
        <View style={st.frame}>
          <View style={[st.corner, { top: 0, left: 0, borderTopWidth: 4, borderLeftWidth: 4, borderTopLeftRadius: 22 }]} />
          <View style={[st.corner, { top: 0, right: 0, borderTopWidth: 4, borderRightWidth: 4, borderTopRightRadius: 22 }]} />
          <View style={[st.corner, { bottom: 0, left: 0, borderBottomWidth: 4, borderLeftWidth: 4, borderBottomLeftRadius: 22 }]} />
          <View style={[st.corner, { bottom: 0, right: 0, borderBottomWidth: 4, borderRightWidth: 4, borderBottomRightRadius: 22 }]} />
        </View>
        <Text style={st.frameText}>
          {!perm ? 'Abrindo câmera…' : perm.granted ? 'Aponte a câmera para o QR Code' : 'A câmera está bloqueada para este app'}
        </Text>
        {perm && !perm.granted && !perm.canAskAgain ? (
          <Text style={st.frameHint}>{SETTINGS_PATH} → Câmera → ativar</Text>
        ) : null}
      </View>

      <View style={[st.bottom, { paddingBottom: ins.bottom + 16 }]}>
        <ErrorBanner message={error} />
        {perm && !perm.granted ? (
          <Button
            title={perm.canAskAgain ? 'Permitir acesso à câmera' : 'Abrir Ajustes'}
            icon="camera"
            onPress={() => (perm.canAskAgain ? requestPerm() : Linking.openSettings())}
            style={{ marginBottom: 10 }}
          />
        ) : null}
        <Button title="Colar Pix copia e cola" icon="clipboard-outline" variant="secondary" onPress={paste} />
      </View>
    </View>
  );
}

const st = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12, marginTop: 6 },
  topTitle: { color: C.text, fontSize: 17, fontWeight: '700' },
  merchant: { flexDirection: 'row', alignItems: 'center', gap: 14, marginTop: 10 },
  avatar: { width: 48, height: 48, borderRadius: 16, backgroundColor: C.redSoft, alignItems: 'center', justifyContent: 'center' },
  mName: { color: C.text, fontSize: 17, fontWeight: '700' },
  mCity: { color: C.muted, fontSize: 13, marginTop: 2 },
  label: { color: C.muted, fontSize: 15, fontWeight: '600' },
  amount: { color: C.text, fontSize: 50, fontWeight: '800', letterSpacing: -1.5, marginTop: 4 },
  insuf: { color: C.redLight, textAlign: 'center', marginTop: 8, fontSize: 13 },
  okIcon: { width: 84, height: 84, borderRadius: 42, backgroundColor: C.green, alignItems: 'center', justifyContent: 'center' },
  okTitle: { color: C.text, fontSize: 24, fontWeight: '800', marginTop: 18 },
  okSub: { color: C.muted, fontSize: 15, marginTop: 6, textAlign: 'center' },
  overlayTop: { position: 'absolute', top: 0, left: 0, right: 0, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 20, zIndex: 2 },
  frameWrap: { ...FILL, alignItems: 'center', justifyContent: 'center' },
  frame: { width: 250, height: 250 },
  corner: { position: 'absolute', width: 54, height: 54, borderColor: C.red },
  frameText: { color: '#fff', marginTop: 22, fontSize: 15, fontWeight: '600', textAlign: 'center' },
  frameHint: { color: C.sub, marginTop: 8, fontSize: 13, textAlign: 'center' },
  bottom: { position: 'absolute', left: 0, right: 0, bottom: 0, paddingHorizontal: 20 },
});
