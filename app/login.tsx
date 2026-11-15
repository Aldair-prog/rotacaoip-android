import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { Image } from 'expo-image';
import { LinearGradient } from 'expo-linear-gradient';
import * as Clipboard from 'expo-clipboard';
import * as SecureStore from 'expo-secure-store';
import { Ionicons } from '@expo/vector-icons';
import { ErrorBanner, Screen, failure, impact, success, tap } from '../src/components/ui';
import { useSession } from '../src/session';
import { ApiError } from '../src/lib/api';
import { C } from '../src/theme';
import { BRAND, LOGO, PANEL_URL } from '../src/brand';
import { BrandLogo } from '../src/components/Intro';
import { router } from 'expo-router';

const WEB = PANEL_URL;
type Step = 'email' | 'password' | 'otp';

// E-mail e senha salvos no cofre seguro do aparelho (Keychain/Keystore) (criptografado, só neste aparelho)
const SAVED_KEY = 'cp_saved_login';
const KC = { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY };
async function loadSaved(): Promise<{ email: string; password: string } | null> {
  try {
    const raw = await SecureStore.getItemAsync(SAVED_KEY, KC);
    const v = raw ? JSON.parse(raw) : null;
    return v && v.email && v.password ? v : null;
  } catch {
    return null;
  }
}
const storeSaved = (email: string, password: string) =>
  SecureStore.setItemAsync(SAVED_KEY, JSON.stringify({ email, password }), KC).catch(() => {});
const clearSaved = () => SecureStore.deleteItemAsync(SAVED_KEY, KC).catch(() => {});

export default function Login() {
  const { signIn, verifyOtp, lastEmail, biometricAvailable, biometricEnabled, biometricLabel, setBiometricEnabled } = useSession();

  // Depois de entrar, oferece a biometria pra não precisar digitar senha da próxima vez
  const offerBiometrics = () => {
    if (!biometricAvailable || biometricEnabled) return;
    setTimeout(
      () =>
        Alert.alert(
          `Entrar com ${biometricLabel}?`,
          `Da próxima vez você entra só com o ${biometricLabel}, sem digitar e-mail e senha. Ele também será pedido toda vez que abrir o app.`,
          [
            { text: 'Agora não', style: 'cancel' },
            { text: `Usar ${biometricLabel}`, onPress: () => setBiometricEnabled(true) },
          ]
        ),
      500
    );
  };
  const [step, setStep] = useState<Step>('email');
  const [email, setEmail] = useState(lastEmail);
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [keep, setKeep] = useState(true);
  const [remember, setRemember] = useState(false);
  const [hasSaved, setHasSaved] = useState(false);
  const [otpToken, setOtpToken] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const passRef = useRef<any>(null);
  const codeRef = useRef<any>(null);

  const emailOk = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());

  // Se tiver login salvo, já preenche e-mail e senha
  useEffect(() => {
    loadSaved().then((v) => {
      if (!v) return;
      setEmail(v.email);
      setPassword(v.password);
      setRemember(true);
      setHasSaved(true);
    });
  }, []);

  // O teclado só abre no código de verificação (lá o cliente sempre vai digitar)
  useEffect(() => {
    if (step === 'otp') setTimeout(() => codeRef.current?.focus(), 250);
  }, [step]);

  const goPassword = () => {
    if (!emailOk) return;
    setError(null);
    setStep('password');
  };

  const submitPassword = async () => {
    if (!password) return;
    setError(null);
    setLoading(true);
    try {
      const r = await signIn(email, password, keep);
      // Senha aceita: salva ou apaga conforme a opção
      if (remember) storeSaved(email.trim(), password);
      else clearSaved();
      setHasSaved(remember);
      if (r.kind === 'otp') {
        setOtpToken(r.otpToken);
        setStep('otp');
      } else {
        success();
        offerBiometrics();
      }
    } catch (e) {
      failure();
      setError(e instanceof ApiError ? e.message : 'Não foi possível entrar. Tente de novo.');
    } finally {
      setLoading(false);
    }
  };

  const submitCode = async (c: string) => {
    if (!otpToken || c.length !== 6 || loading) return;
    setError(null);
    setLoading(true);
    try {
      await verifyOtp(c, otpToken, keep);
      success();
      offerBiometrics();
    } catch (e) {
      failure();
      setCode('');
      setError(e instanceof ApiError ? e.message : 'Código inválido. Tente de novo.');
    } finally {
      setLoading(false);
    }
  };

  const onCode = (v: string) => {
    const d = v.replace(/\D/g, '').slice(0, 6);
    setCode(d);
    if (d.length === 6) submitCode(d);
  };

  const handlePasted = (raw: string) => {
    const txt = (raw ?? '').replace(/\D/g, '');
    if (txt.length >= 6) {
      setError(null);
      onCode(txt.slice(0, 6));
    } else {
      failure();
      setError(
        raw
          ? 'O que foi colado não é um código de 6 números. Copie de novo no Google Authenticator.'
          : 'Nada foi colado. Se o celular perguntar, toque em "Permitir Colar".'
      );
    }
  };

  const pasteCode = async () => {
    tap();
    handlePasted(await Clipboard.getStringAsync().catch(() => ''));
  };

  const back = () => {
    tap();
    setError(null);
    setPassword('');
    setCode('');
    setOtpToken(null);
    setStep('email');
  };

  const initials = email.trim().slice(0, 2).toUpperCase();

  const openPage = (path: string, title: string) => {
    tap();
    router.push({ pathname: '/documento', params: { url: `${WEB}${path}`, title } });
  };
  const canSubmit = emailOk && !!password;

  return (
    <Screen bottomPad={40}>
      {/* Barra de progresso: entrar → código de verificação */}
      <View style={st.progress}>
        <View style={[st.progressFill, { width: step === 'otp' ? '100%' : canSubmit ? '50%' : '25%' }]} />
      </View>

      {step !== 'otp' && (
        <>
          {/* Logo grande e animada no topo */}
          <View style={st.hero}>
            <BrandLogo size={104} />
            <Text style={st.welcome}>Bem-vindo</Text>
            <Text style={st.welcomeSub}>Acesse sua conta {BRAND.name}</Text>
          </View>

          <Field label="E-mail" icon="mail-outline">
            <TextInput
              value={email}
              onChangeText={(v) => {
                setEmail(v);
                setError(null);
              }}
              placeholder="voce@email.com"
              placeholderTextColor={C.dim}
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="email"
              keyboardType="email-address"
              textContentType="username"
              returnKeyType="next"
              onSubmitEditing={() => passRef.current?.focus()}
              style={st.input}
            />
          </Field>

          <Field label="Senha" icon="lock-closed-outline">
            <TextInput
              ref={passRef}
              value={password}
              onChangeText={(v) => {
                setPassword(v);
                setError(null);
              }}
              placeholder="Sua senha"
              placeholderTextColor={C.dim}
              secureTextEntry={!show}
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="current-password"
              textContentType="password"
              returnKeyType="go"
              onSubmitEditing={() => canSubmit && submitPassword()}
              style={st.input}
            />
            <Pressable hitSlop={10} onPress={() => setShow((v) => !v)} accessibilityLabel="Mostrar ou ocultar senha">
              <Ionicons name={show ? 'eye-off-outline' : 'eye-outline'} size={22} color={C.muted} />
            </Pressable>
          </Field>

          <Text style={[st.linkSmall, { alignSelf: 'flex-end', marginTop: 12 }]} onPress={() => openPage('/reset-password', 'Recuperar senha')}>
            Esqueceu a senha?
          </Text>

          <ErrorBanner message={error} />

          <PillButton title="Entrar" icon="arrow-forward" onPress={submitPassword} loading={loading} disabled={!canSubmit} full />

          <View style={st.optCard}>
            <View style={st.keepRow2}>
              <Ionicons name="time-outline" size={20} color={C.muted} />
              <Text style={st.keepText2}>Permanecer conectado</Text>
              <Switch value={keep} onValueChange={setKeep} trackColor={{ true: C.red }} />
            </View>
            <View style={st.optLine} />
            <View style={st.keepRow2}>
              <Ionicons name="key-outline" size={20} color={C.muted} />
              <View style={{ flex: 1 }}>
                <Text style={st.keepText2}>Salvar e-mail e senha</Text>
                <Text style={st.keepSub}>Guardado com segurança só neste aparelho</Text>
              </View>
              <Switch
                value={remember}
                onValueChange={(v) => {
                  tap();
                  setRemember(v);
                  if (!v) {
                    clearSaved();
                    setHasSaved(false);
                  }
                }}
                trackColor={{ true: C.red }}
              />
            </View>
          </View>

          <Text style={[st.sub, { textAlign: 'center', marginTop: 24 }]}>
            Ainda não tem conta?{' '}
            <Text style={st.link} onPress={() => openPage('/sign-up', 'Criar conta')}>
              Criar conta
            </Text>
          </Text>
        </>
      )}

      {step === 'otp' && (
        <>
          <Text style={st.title}>Verificação em duas etapas</Text>
          <Text style={st.sub}>Digite o código de 6 dígitos do seu app autenticador.</Text>

          <Pressable onPress={() => codeRef.current?.focus()} style={st.codeRow}>
            {Array.from({ length: 6 }).map((_, i) => {
              const ch = code[i];
              const active = i === code.length && !loading;
              return (
                <View key={i} style={[st.codeBox, active && { borderColor: C.red }, ch && { borderColor: C.borderStrong }]}>
                  <Text style={st.codeText}>{ch ?? ''}</Text>
                </View>
              );
            })}
            {/* Campo invisível por cima das caixinhas: aceita digitar, colar (segurar o dedo → Colar) e o preenchimento automático */}
            <TextInput
              ref={codeRef}
              value={code}
              onChangeText={onCode}
              keyboardType="number-pad"
              textContentType="oneTimeCode"
              autoComplete="one-time-code"
              caretHidden
              selectionColor="transparent"
              contextMenuHidden={false}
              style={st.codeInput}
            />
          </Pressable>

          {loading ? <ActivityIndicator color={C.red} style={{ marginTop: 20 }} /> : null}
          <ErrorBanner message={error} />

          {Clipboard.isPasteButtonAvailable ? (
            // Botão de colar nativo da Apple: cola direto, sem a pergunta "Permitir colar?"
            <View style={st.nativePasteWrap}>
              <Clipboard.ClipboardPasteButton
                onPress={(d: any) => handlePasted(d?.type === 'text' ? d.text : '')}
                acceptedContentTypes={['plain-text']}
                displayMode="iconAndLabel"
                cornerStyle="capsule"
                backgroundColor={C.red}
                foregroundColor="#FFFFFF"
                style={st.nativePaste}
              />
            </View>
          ) : (
            <Pressable onPress={pasteCode} style={({ pressed }) => [st.pasteBtn, { opacity: pressed ? 0.7 : 1 }]}>
              <Ionicons name="clipboard-outline" size={20} color={C.text} />
              <Text style={st.pasteText}>Colar código</Text>
            </Pressable>
          )}

          <Text style={[st.link, { marginTop: 22, textAlign: 'center' }]} onPress={back}>
            Voltar
          </Text>
        </>
      )}

      <View style={st.secure}>
        <Ionicons name="shield-checkmark" size={16} color={C.green} />
        <Text style={st.secureText}>Conexão segura. Sua sessão fica guardada só neste aparelho.</Text>
      </View>
    </Screen>
  );
}

function Field({ label, children, icon }: { label: string; children: React.ReactNode; icon?: keyof typeof Ionicons.glyphMap }) {
  return (
    <View style={st.field}>
      <Text style={st.fieldLabel}>{label}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
        {icon ? <Ionicons name={icon} size={20} color={C.muted} /> : null}
        {children}
      </View>
    </View>
  );
}

function PillButton({
  title,
  icon,
  onPress,
  disabled,
  loading,
  full,
}: {
  full?: boolean;
  title: string;
  icon?: keyof typeof Ionicons.glyphMap;
  onPress: () => void;
  disabled?: boolean;
  loading?: boolean;
}) {
  return (
    <Pressable
      disabled={disabled || loading}
      onPress={() => {
        impact();
        onPress();
      }}
      style={({ pressed }) => [st.pill, full && { alignSelf: 'stretch', marginTop: 22 }, { opacity: disabled ? 0.45 : pressed ? 0.85 : 1 }]}
    >
      {loading ? (
        <ActivityIndicator color="#fff" />
      ) : (
        <>
          <Text style={st.pillText}>{title}</Text>
          {icon ? <Ionicons name={icon} size={20} color="#fff" /> : null}
        </>
      )}
    </Pressable>
  );
}

const st = StyleSheet.create({
  progress: { height: 3, backgroundColor: 'rgba(255,255,255,0.12)', borderRadius: 2, marginTop: 4, marginBottom: 18, overflow: 'hidden' },
  hero: { alignItems: 'center', marginBottom: 6 },
  welcome: { color: C.text, fontSize: 34, fontWeight: '900', letterSpacing: -0.8, marginTop: 2 },
  welcomeSub: { color: C.muted, fontSize: 15.5, marginTop: 6 },
  linkSmall: { color: C.redLight, fontSize: 14.5, fontWeight: '700' },
  optCard: { marginTop: 22, backgroundColor: '#121317', borderRadius: 16, borderWidth: 1, borderColor: C.border, paddingHorizontal: 14 },
  optLine: { height: 1, backgroundColor: C.border },
  keepRow2: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 12 },
  keepText2: { flex: 1, color: C.text, fontSize: 15.5, fontWeight: '600' },
  progressFill: { height: 3, backgroundColor: C.red },
  logo: { width: 92, height: 85 },
  title: { color: C.text, fontSize: 38, fontWeight: '800', letterSpacing: -1, marginTop: 18 },
  sub: { color: C.muted, fontSize: 16, marginTop: 10, lineHeight: 22 },
  link: { color: C.redLight, fontSize: 16, fontWeight: '700' },
  field: {
    marginTop: 16,
    backgroundColor: '#15161A',
    borderRadius: 12,
    borderWidth: 1.5,
    borderColor: C.borderStrong,
    paddingHorizontal: 18,
    paddingTop: 12,
    paddingBottom: 10,
  },
  fieldLabel: { color: C.redLight, fontSize: 14, fontWeight: '600', marginBottom: 4 },
  input: { flex: 1, color: C.text, fontSize: 18, paddingVertical: 4 },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    backgroundColor: C.red,
    borderRadius: 30,
    paddingHorizontal: 30,
    height: 56,
    minWidth: 150,
  },
  pillText: { color: '#fff', fontSize: 18, fontWeight: '700' },
  user: { flexDirection: 'row', alignItems: 'center', gap: 14, marginTop: 26 },
  avatar: { width: 54, height: 54, borderRadius: 27, alignItems: 'center', justifyContent: 'center' },
  avatarText: { color: '#fff', fontSize: 19, fontWeight: '800' },
  userEmail: { color: C.text, fontSize: 17, flexShrink: 1 },
  keepRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 18,
    backgroundColor: '#15161A',
    borderRadius: 12,
    paddingHorizontal: 18,
    paddingVertical: 12,
  },
  keepText: { color: C.text, fontSize: 16, fontWeight: '500' },
  keepSub: { color: C.muted, fontSize: 12.5, marginTop: 2 },
  codeRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 30 },
  codeBox: {
    width: 50,
    height: 60,
    borderRadius: 14,
    backgroundColor: '#15161A',
    borderWidth: 1.5,
    borderColor: C.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  codeText: { color: C.text, fontSize: 26, fontWeight: '700' },
  codeInput: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, color: 'transparent', fontSize: 1, opacity: 0.02 },
  pasteBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    height: 54,
    borderRadius: 30,
    marginTop: 24,
    backgroundColor: '#15161A',
    borderWidth: 1,
    borderColor: C.borderStrong,
  },
  pasteText: { color: C.text, fontSize: 17, fontWeight: '700' },
  nativePasteWrap: { marginTop: 24, alignItems: 'stretch' },
  nativePaste: { height: 54, width: '100%' },
  secure: { flexDirection: 'row', gap: 8, alignItems: 'center', justifyContent: 'center', marginTop: 40, paddingHorizontal: 10 },
  secureText: { color: C.muted, fontSize: 13, flexShrink: 1, textAlign: 'center' },
});
