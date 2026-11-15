import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, AppStateStatus, Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import * as LocalAuthentication from 'expo-local-authentication';
import * as Crypto from 'expo-crypto';
import { api, auth, Company, getSession, onAuthChange, Session, setDevice, setSession, SignInResult } from './lib/api';
import { BRAND } from './brand';
import { unregisterPush } from './lib/notify';

const K = {
  refresh: 'cp_refresh_token',
  email: 'cp_last_email',
  bio: 'cp_biometric',
  hide: 'cp_hide_values',
  device: 'cp_device_id',
};

const LOCK_AFTER_MS = 30_000;

// Único e-mail com acesso ao Painel Admin pelo app (guardado como hash SHA-256, o e-mail não fica no código)
const isAdminEmail = async (e?: string | null) => {
  const clean = (e ?? '').trim().toLowerCase();
  if (!BRAND.adminHash || !clean) return false;
  const h = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, clean).catch(() => '');
  return h === BRAND.adminHash;
};

type Status = 'loading' | 'signedOut' | 'signedIn';

type Ctx = {
  status: Status;
  company: Company | null;
  isAdmin: boolean;
  lastEmail: string;
  locked: boolean;
  biometricEnabled: boolean;
  biometricAvailable: boolean;
  biometricLabel: string;
  hideValues: boolean;
  appActive: boolean;
  signIn: (email: string, password: string, keep: boolean) => Promise<SignInResult>;
  verifyOtp: (code: string, otpToken: string, keep: boolean) => Promise<void>;
  signOut: () => Promise<void>;
  /** Só para o teste automático: abre as telas internas sem conta (nenhum dado real é carregado) */
  testSignIn: () => void;
  unlock: () => Promise<boolean>;
  confirmIdentity: (reason: string) => Promise<boolean>;
  setBiometricEnabled: (v: boolean) => Promise<void>;
  setHideValues: (v: boolean) => void;
  refreshCompany: () => Promise<void>;
};

const SessionCtx = createContext<Ctx | null>(null);

export function useSession() {
  const s = useContext(SessionCtx);
  if (!s) throw new Error('useSession fora do SessionProvider');
  return s;
}

const secureOpts: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<Status>('loading');
  const [company, setCompany] = useState<Company | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);
  const [lastEmail, setLastEmail] = useState('');
  const [locked, setLocked] = useState(false);
  const [biometricEnabled, setBio] = useState(false);
  const [biometricAvailable, setBioAvailable] = useState(false);
  const [biometricLabel, setBioLabel] = useState('Face ID');
  const [hideValues, setHide] = useState(false);
  const [appActive, setAppActive] = useState(true);
  const keepRef = useRef(true);
  const lastEmailRef = useRef('');
  const backgroundAt = useRef<number | null>(null);

  // Quando o token é renovado, guarda o novo refresh token. Se a sessão cai, volta pro login.
  useEffect(() => {
    onAuthChange((s) => {
      if (!s) {
        SecureStore.deleteItemAsync(K.refresh).catch(() => {});
        setCompany(null);
        setLocked(false);
        setStatus('signedOut');
      } else if (keepRef.current && s.refreshToken) {
        SecureStore.setItemAsync(K.refresh, s.refreshToken, secureOpts).catch(() => {});
      }
    });
  }, []);

  // Inicialização
  useEffect(() => {
    (async () => {
      try {
        const [hasHw, enrolled, types] = await Promise.all([
          LocalAuthentication.hasHardwareAsync(),
          LocalAuthentication.isEnrolledAsync(),
          LocalAuthentication.supportedAuthenticationTypesAsync(),
        ]);
        const available = hasHw && enrolled;
        setBioAvailable(available);
        const face = types.includes(LocalAuthentication.AuthenticationType.FACIAL_RECOGNITION);
        const finger = types.includes(LocalAuthentication.AuthenticationType.FINGERPRINT);
        // iPhone: Face ID / Touch ID · Android: digital / biometria
        setBioLabel(Platform.OS === 'ios' ? (face ? 'Face ID' : 'Touch ID') : finger ? 'digital' : 'biometria');

        let deviceId = await SecureStore.getItemAsync(K.device);
        if (!deviceId) {
          deviceId = Crypto.randomUUID();
          await SecureStore.setItemAsync(K.device, deviceId);
        }
        setDevice(deviceId, deviceId.replace(/-/g, ''));

        const [refresh, email, bio, hide] = await Promise.all([
          SecureStore.getItemAsync(K.refresh),
          SecureStore.getItemAsync(K.email),
          SecureStore.getItemAsync(K.bio),
          SecureStore.getItemAsync(K.hide),
        ]);
        setHide(hide === '1');
        setLastEmail(email ?? '');
        lastEmailRef.current = email ?? '';

        if (refresh) {
          const s = await auth.resume(refresh).catch(() => null);
          if (s) {
            const bioOn = bio === '1' && available;
            setBio(bioOn);
            setLocked(bioOn);
            setStatus('signedIn');
            api.company().then(setCompany).catch(() => {});
            isAdminEmail(email).then(setIsAdmin);
            api.me().then((u) => isAdminEmail(u?.email ?? email).then(setIsAdmin)).catch(() => {});
            return;
          }
          await SecureStore.deleteItemAsync(K.refresh);
        }
        setStatus('signedOut');
      } catch {
        setStatus('signedOut');
      }
    })();
  }, []);

  // Bloqueio automático ao voltar do segundo plano + escudo de privacidade
  useEffect(() => {
    const sub = AppState.addEventListener('change', (next: AppStateStatus) => {
      setAppActive(next === 'active');
      if (next === 'background') backgroundAt.current = Date.now();
      if (next === 'active' && backgroundAt.current) {
        const away = Date.now() - backgroundAt.current;
        backgroundAt.current = null;
        if (away > LOCK_AFTER_MS && biometricEnabled && status === 'signedIn') setLocked(true);
      }
    });
    return () => sub.remove();
  }, [biometricEnabled, status]);

  const authenticate = useCallback(async (reason: string) => {
    const r = await LocalAuthentication.authenticateAsync({
      promptMessage: reason,
      cancelLabel: 'Cancelar',
      fallbackLabel: 'Usar código',
      disableDeviceFallback: false,
    });
    return r.success;
  }, []);

  const unlock = useCallback(async () => {
    const ok = await authenticate(`Desbloquear o ${BRAND.name}`);
    if (ok) setLocked(false);
    return ok;
  }, [authenticate]);

  const confirmIdentity = useCallback(
    async (reason: string) => {
      const hasHw = await LocalAuthentication.hasHardwareAsync();
      if (!hasHw) return true;
      return authenticate(reason);
    },
    [authenticate]
  );

  const finish = useCallback(async (s: Session, keep: boolean) => {
    keepRef.current = keep;
    setSession(s);
    if (keep && s.refreshToken) await SecureStore.setItemAsync(K.refresh, s.refreshToken, secureOpts);
    else await SecureStore.deleteItemAsync(K.refresh);
    setStatus('signedIn');
    api.company().then(setCompany).catch(() => {});
    api.me().then((u) => isAdminEmail(u?.email ?? lastEmailRef.current).then(setIsAdmin)).catch(() => {});
  }, []);

  const signIn = useCallback(
    async (email: string, password: string, keep: boolean) => {
      const r = await auth.signIn(email, password);
      const clean = email.trim().toLowerCase();
      setLastEmail(clean);
      lastEmailRef.current = clean;
      isAdminEmail(clean).then(setIsAdmin);
      SecureStore.setItemAsync(K.email, clean).catch(() => {});
      if (r.kind === 'ok') await finish(r.session, keep);
      return r;
    },
    [finish]
  );

  const verifyOtp = useCallback(
    async (code: string, otpToken: string, keep: boolean) => {
      const s = await auth.verifyOtp(code, otpToken);
      await finish(s, keep);
    },
    [finish]
  );

  const signOut = useCallback(async () => {
    // tira este aparelho da lista de push da conta antes de sair
    await unregisterPush().catch(() => {});
    await Promise.all([SecureStore.deleteItemAsync(K.refresh), SecureStore.deleteItemAsync(K.bio)]);
    setSession(null);
    setCompany(null);
    setIsAdmin(false);
    setBio(false);
    setLocked(false);
    setStatus('signedOut');
  }, []);

  const setBiometricEnabled = useCallback(
    async (v: boolean) => {
      if (v) {
        const ok = await authenticate('Ativar entrada com biometria');
        if (!ok) return;
        // Com biometria ligada a sessão fica guardada: na próxima vez entra só com o rosto
        keepRef.current = true;
        const rt = getSession()?.refreshToken;
        if (rt) await SecureStore.setItemAsync(K.refresh, rt, secureOpts);
      }
      await SecureStore.setItemAsync(K.bio, v ? '1' : '0');
      setBio(v);
    },
    [authenticate]
  );

  const setHideValues = useCallback((v: boolean) => {
    setHide(v);
    SecureStore.setItemAsync(K.hide, v ? '1' : '0').catch(() => {});
  }, []);

  const refreshCompany = useCallback(async () => {
    try {
      setCompany(await api.company());
    } catch {
      /* a camada da API já trata sessão expirada */
    }
  }, []);

  const value = useMemo<Ctx>(
    () => ({
      status,
      company,
      isAdmin,
      lastEmail,
      locked,
      biometricEnabled,
      biometricAvailable,
      biometricLabel,
      hideValues,
      appActive,
      signIn,
      verifyOtp,
      signOut,
      testSignIn: () => setStatus('signedIn'),
      unlock,
      confirmIdentity,
      setBiometricEnabled,
      setHideValues,
      refreshCompany,
    }),
    [status, company, isAdmin, lastEmail, locked, biometricEnabled, biometricAvailable, biometricLabel, hideValues, appActive, signIn, verifyOtp, signOut, unlock, confirmIdentity, setBiometricEnabled, setHideValues, refreshCompany]
  );

  return <SessionCtx.Provider value={value}>{children}</SessionCtx.Provider>;
}
