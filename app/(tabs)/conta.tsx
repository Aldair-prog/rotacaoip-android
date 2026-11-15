import React, { useEffect, useState } from 'react';
import { router } from 'expo-router';
import { Alert, Linking, StyleSheet, Switch, Text, View } from 'react-native';
import { Image } from 'expo-image';
import Constants from 'expo-constants';
import { Ionicons } from '@expo/vector-icons';
import { Badge, Button, Card, Header, Row, Screen, SectionTitle } from '../../src/components/ui';
import { feeText, maskDoc } from '../../src/lib/format';
import { api, fileUrl, TenantSettings } from '../../src/lib/api';
import { useSession } from '../../src/session';
import { C } from '../../src/theme';
import { notificationsEnabled, sendTestNotification, setNotificationsEnabled } from '../../src/lib/notify';
import { BRAND, A, LOGO, SETTINGS_PATH, DEVICE, BIO_ICON } from '../../src/brand';
import { BackgroundRow } from '../../src/components/BackgroundRow';


export default function Conta() {
  const {
    company,
    biometricAvailable,
    biometricEnabled,
    biometricLabel,
    setBiometricEnabled,
    hideValues,
    setHideValues,
    signOut,
    refreshCompany,
  } = useSession();

  const [cfg, setCfg] = useState<TenantSettings | null>(null);
  const [notif, setNotif] = useState(true);
  useEffect(() => {
    notificationsEnabled().then(setNotif);
  }, []);
  const toggleNotif = async (on: boolean) => {
    setNotif(on);
    const ok = await setNotificationsEnabled(on);
    if (on && !ok) {
      Alert.alert('Notificações bloqueadas', `Ative em ${SETTINGS_PATH} → Notificações.`, [
        { text: 'Agora não', style: 'cancel' },
        { text: 'Abrir Ajustes', onPress: () => Linking.openSettings() },
      ]);
    } else if (on) sendTestNotification().catch(() => {});
  };
  // Taxa real de saque: a mesma simulação que o painel usa (a do cadastro pode vir zerada)
  const [wdFee, setWdFee] = useState<{ fixed: number; percent: number } | null>(null);
  useEffect(() => {
    api.settings().then(setCfg).catch(() => {});
    api
      .feePreview(10000)
      .then((f) => setWdFee({ fixed: f.fixed_fee, percent: f.percent_fee }))
      .catch(() => {});
  }, []);
  const terms = fileUrl(cfg?.terms_url);
  const privacy = fileUrl(cfg?.privacy_url);
  const wa = cfg?.whatsapp_number ? `https://wa.me/${cfg.whatsapp_number.replace(/\D/g, '').replace(/^(?!55)/, '55')}` : null;

  const fees = company?.fees;
  const approved = company?.status === 'APPROVED';

  const logout = () =>
    Alert.alert('Desconectar conta?', 'Você vai precisar entrar com e-mail e senha de novo neste aparelho.', [
      { text: 'Cancelar', style: 'cancel' },
      { text: 'Desconectar', style: 'destructive', onPress: signOut },
    ]);

  return (
    <Screen onRefresh={() => Promise.all([refreshCompany(), api.settings().then(setCfg).catch(() => {})])}>
      <Header title="Conta" />

      <Card style={st.profile}>
        <View style={st.avatar}>
          <Image source={LOGO} style={{ width: 40, height: 37 }} contentFit="contain" />
        </View>
        <Text style={st.name} numberOfLines={2}>
          {company?.trade_name || company?.name || 'Sua empresa'}
        </Text>
        {company?.trade_name && company?.name ? <Text style={st.legal}>{company.name}</Text> : null}
        {company?.document ? (
          <Text style={st.doc}>
            {company.document.type} {maskDoc(company.document.number)}
          </Text>
        ) : null}
        <View style={{ marginTop: 10 }}>
          <Badge
            label={approved ? 'Conta verificada' : company?.status ? `Status: ${company.status}` : '—'}
            color={approved ? C.green : C.orange}
            bg={approved ? C.greenSoft : C.orangeSoft}
          />
        </View>
      </Card>

      <SectionTitle title="Segurança" />
      <Card style={{ paddingVertical: 4 }}>
        <Row
          icon={BIO_ICON}
          iconColor={C.redLight}
          iconBg={C.redSoft}
          title={`Entrar com ${biometricLabel}`}
          subtitle={biometricAvailable ? 'Ao abrir o app e antes de enviar dinheiro' : `Configure nos ajustes do ${DEVICE}`}
          right={<Switch value={biometricEnabled} disabled={!biometricAvailable} onValueChange={setBiometricEnabled} trackColor={{ true: C.red }} />}
        />
        <Row
          icon="eye-off"
          iconColor={C.silver}
          title="Ocultar valores"
          subtitle="Esconde saldos e valores na tela inicial"
          right={<Switch value={hideValues} onValueChange={setHideValues} trackColor={{ true: C.red }} />}
        />
        <Row
          icon="notifications"
          iconColor={C.redLight}
          iconBg={C.redSoft}
          title="Notificações"
          subtitle="Avisa cada Pix recebido e cada saque"
          right={<Switch value={notif} onValueChange={toggleNotif} trackColor={{ true: C.red }} />}
          last
        />
      </Card>
      <BackgroundRow />

      <SectionTitle title="Suas taxas" />
      <Card style={{ paddingVertical: 4 }}>
        <Row
          icon="arrow-down"
          iconColor={C.green}
          iconBg={C.greenSoft}
          title="Receber Pix"
          right={<Text style={st.fee}>{fees?.incoming?.pix?.available === false ? 'Indisponível' : feeText(fees?.incoming?.pix?.fixed, fees?.incoming?.pix?.percentage)}</Text>}
        />
        {fees?.incoming?.credit_card?.available ? (
          <Row
            icon="card"
            iconColor={C.blue}
            iconBg="rgba(10,132,255,0.15)"
            title="Cartão de crédito"
            right={<Text style={st.fee}>{feeText(fees.incoming.credit_card.fixed, fees.incoming.credit_card.percentage)}</Text>}
          />
        ) : null}
        <Row
          icon="arrow-up"
          iconColor={C.redLight}
          iconBg={C.redSoft}
          title="Enviar Pix / Saque"
          right={
            <Text style={st.fee}>
              {wdFee ? feeText(wdFee.fixed, wdFee.percent) : feeText(company?.withdraw_fee_fixed ?? fees?.outgoing?.fixed, company?.withdraw_fee_percentage ?? fees?.outgoing?.percentage)}
            </Text>
          }
          last
        />
      </Card>

      <SectionTitle title="Sobre" />
      <Card style={{ paddingVertical: 4 }}>
        <Row
          icon="document-text"
          title="Termos de uso"
          right={<Ionicons name="chevron-forward" size={18} color={C.dim} />}
          onPress={() => terms && router.push({ pathname: '/documento', params: { url: terms, title: 'Termos de uso' } })}
        />
        <Row
          icon="lock-closed"
          title="Política de privacidade"
          right={<Ionicons name="chevron-forward" size={18} color={C.dim} />}
          onPress={() => privacy && router.push({ pathname: '/documento', params: { url: privacy, title: 'Política de privacidade' } })}
        />
        <Row
          icon="logo-whatsapp"
          iconColor={C.green}
          iconBg={C.greenSoft}
          title="Suporte no WhatsApp"
          subtitle={`Fale com a equipe ${BRAND.name}`}
          right={<Ionicons name="chevron-forward" size={18} color={C.dim} />}
          onPress={() => Linking.openURL(BRAND.support ?? wa ?? BRAND.contact)}
        />
        <Row icon="information-circle" title="Versão do app" right={<Text style={st.fee}>{Constants.expoConfig?.version ?? '1.0.0'}</Text>} last />
      </Card>

      <Button title="Desconectar conta" icon="log-out-outline" variant="danger" onPress={logout} style={{ marginTop: 26 }} />
      <Text style={st.foot}>{cfg?.slogan ? cfg.slogan : BRAND.slogan}</Text>
      <Text style={st.copy}>© {new Date().getFullYear()} {BRAND.copyright}{'\n'}Todos os direitos reservados</Text>
    </Screen>
  );
}

const st = StyleSheet.create({
  profile: { alignItems: 'center', paddingVertical: 24 },
  avatar: {
    width: 76,
    height: 76,
    borderRadius: 38,
    backgroundColor: C.card2,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: A(0.45),
    marginBottom: 14,
  },
  name: { color: C.text, fontSize: 20, fontWeight: '800', textAlign: 'center' },
  legal: { color: C.muted, fontSize: 13, marginTop: 3, textAlign: 'center' },
  doc: { color: C.sub, fontSize: 14, marginTop: 6 },
  fee: { color: C.sub, fontSize: 14, fontWeight: '600' },
  foot: { color: C.dim, textAlign: 'center', marginTop: 22, fontSize: 12, letterSpacing: 0.5 },
  copy: { color: C.dim, textAlign: 'center', marginTop: 8, fontSize: 10.5, lineHeight: 15, letterSpacing: 0.6, opacity: 0.8 },
});
