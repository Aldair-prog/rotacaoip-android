import { useEffect, useRef } from 'react';
import { AppState, Platform } from 'react-native';
import * as Notifications from 'expo-notifications';
import * as SecureStore from 'expo-secure-store';
import { router } from 'expo-router';
import Constants from 'expo-constants';
import { api, AppNotification } from './api';
import { BRAND } from '../brand';
import { AppPush } from '../../modules/app-push';

/**
 * Notificações do app (iPhone e Android).
 * Lê a central de notificações do painel (/notifications/@me) e mostra cada aviso novo
 * como banner do iOS ("Transferência recebida", "Saque concluído"...).
 */

const SEEN_KEY = 'cp_notif_seen'; // data do último aviso já mostrado
const ENABLED_KEY = 'cp_notif_enabled';
const POLL_MS = 12_000;
const PUSH_KEY = 'cp_push_endpoint';
// iPhone: servidor que recebe o push do painel e entrega pelo Expo/Apple (Square Cloud, só Connect por enquanto)
const RELAY = BRAND.id === 'connect' ? 'https://connect-push.squareweb.app' : null;
// Android: o próprio app recebe o push do painel (módulo nativo, sem Firebase)
const ANDROID = Platform.OS === 'android' && !!AppPush;
// No Expo Go (teste) o som próprio não existe: usa o som padrão do celular
const SOUND = Constants.executionEnvironment === 'storeClient' ? 'default' : BRAND.sound;
if (AppPush) {
  try {
    AppPush.configure(BRAND.name, BRAND.accent, BRAND.sound.replace(/\.[a-z0-9]+$/i, ''), (Constants.expoConfig as any)?.scheme ?? '');
  } catch {}
}

/**
 * Push de verdade (chega com o app fechado): registra este iPhone no painel,
 * igual ao botão "Notificações push neste dispositivo" das configurações.
 */
export async function registerPush(): Promise<boolean> {
  if (ANDROID) return registerAndroid();
  try {
    if (!RELAY) return false;
    const projectId =
      (Constants as any).easConfig?.projectId || (Constants.expoConfig as any)?.extra?.eas?.projectId;
    if (!projectId) return false;
    const { data: token } = await Notifications.getExpoPushTokenAsync({ projectId });
    const r = await fetch(`${RELAY}/register`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ token }),
    });
    if (!r.ok) return false;
    const sub = await r.json();
    const old = await SecureStore.getItemAsync(PUSH_KEY).catch(() => null);
    if (old && old !== sub.endpoint) await api.pushUnsubscribe(old).catch(() => {});
    await api.pushSubscribe(sub);
    await SecureStore.setItemAsync(PUSH_KEY, sub.endpoint);
    return true;
  } catch {
    return false;
  }
}

/** Android: entrega ao painel a assinatura do próprio celular e liga a recepção */
async function registerAndroid(): Promise<boolean> {
  try {
    const sub = AppPush!.getSubscription();
    const old = await SecureStore.getItemAsync(PUSH_KEY).catch(() => null);
    if (old && old !== sub.endpoint) await api.pushUnsubscribe(old).catch(() => {});
    await api.pushSubscribe(sub);
    await SecureStore.setItemAsync(PUSH_KEY, sub.endpoint);
    AppPush!.setSubscribed(true);
    return true;
  } catch {
    return false;
  }
}

export async function unregisterPush() {
  if (ANDROID) AppPush!.setSubscribed(false);
  const old = await SecureStore.getItemAsync(PUSH_KEY).catch(() => null);
  if (old) await api.pushUnsubscribe(old).catch(() => {});
  await SecureStore.deleteItemAsync(PUSH_KEY).catch(() => {});
}

async function pushActive() {
  return !!(await SecureStore.getItemAsync(PUSH_KEY).catch(() => null));
}

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: true,
  }),
});

export async function notificationsEnabled() {
  return (await SecureStore.getItemAsync(ENABLED_KEY).catch(() => null)) !== '0';
}

export async function setNotificationsEnabled(on: boolean) {
  await SecureStore.setItemAsync(ENABLED_KEY, on ? '1' : '0').catch(() => {});
  if (on) {
    const ok = await askPermission();
    if (ok) registerPush();
    return ok;
  }
  unregisterPush();
  Notifications.setBadgeCountAsync(0).catch(() => {});
  return true;
}

export async function askPermission() {
  try {
    const cur = await Notifications.getPermissionsAsync();
    if (cur.granted) return true;
    if (!cur.canAskAgain) return false;
    const r = await Notifications.requestPermissionsAsync({
      ios: { allowAlert: true, allowBadge: true, allowSound: true },
    });
    return r.granted;
  } catch {
    return false;
  }
}

/** Mostra um aviso de teste na hora */
export async function sendTestNotification() {
  if (ANDROID) {
    // passa pelo mesmo caminho do push do painel; se falhar, mostra direto
    const ok = await AppPush!.sendTest().catch(() => false);
    if (!ok || !AppPush!.isSubscribed()) AppPush!.notify(BRAND.name, 'Notificações ativadas. Você será avisado de cada Pix.');
    return;
  }
  await Notifications.scheduleNotificationAsync({
    content: { title: BRAND.name, body: 'Notificações ativadas. Você será avisado de cada Pix.', sound: SOUND },
    trigger: null,
  });
}

async function show(n: AppNotification) {
  if (ANDROID) {
    AppPush!.notify(n.title || BRAND.name, n.message || '');
    return;
  }
  await Notifications.scheduleNotificationAsync({
    content: {
      title: n.title || BRAND.name,
      body: n.message || '',
      sound: SOUND,
      data: { id: n.id, category: n.category },
    },
    trigger: null,
  });
}

/** Android: o app pode ser pausado pela economia de bateria (avisos atrasam) */
export function batteryRestricted() {
  try {
    return ANDROID ? !AppPush!.isIgnoringBatteryOptimizations() : false;
  } catch {
    return false;
  }
}
export function openBatterySettings() {
  try {
    AppPush?.requestIgnoreBatteryOptimizations();
  } catch {}
}

async function check(first: { current: boolean }) {
  if (!(await notificationsEnabled())) return;
  // Pix recebido, saque concluído/recusado + anúncios enviados pelo painel admin
  const [list, ann] = await Promise.all([
    api.notifications(10),
    api.announcements().catch(() => [] as AppNotification[]),
  ]);
  const items = [...list, ...ann];
  const seen = Number((await SecureStore.getItemAsync(SEEN_KEY).catch(() => null)) || 0);
  const newest = items.reduce((m, n) => Math.max(m, Date.parse(n.created_at) || 0), 0);

  // Primeira vez neste aparelho: só marca o ponto de partida, não dispara avisos antigos
  if (!seen) {
    if (newest) await SecureStore.setItemAsync(SEEN_KEY, String(newest)).catch(() => {});
    first.current = false;
    return;
  }

  const fresh = items
    .filter((n) => (Date.parse(n.created_at) || 0) > seen)
    .sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at));

  if (fresh.length) {
    await SecureStore.setItemAsync(SEEN_KEY, String(newest)).catch(() => {});
    // Com o push ligado o aviso já chega pelo servidor; aqui só cobre quando o push não está ativo
    if (!(await pushActive())) for (const n of fresh.slice(-3)) await show(n);
  }

  const unread = items.filter((n) => !n.read_at).length;
  Notifications.setBadgeCountAsync(unread).catch(() => {});
  first.current = false;
}

/** Fica de olho nas notificações enquanto o usuário está conectado */
export function useNotificationWatcher(active: boolean) {
  const first = useRef(true);
  const busy = useRef(false);

  useEffect(() => {
    if (!active) return;
    let timer: ReturnType<typeof setInterval> | null = null;

    const run = async () => {
      if (busy.current) return;
      busy.current = true;
      try {
        await check(first);
      } catch {
        // sem internet / sessão renovando: tenta no próximo ciclo
      } finally {
        busy.current = false;
      }
    };

    (async () => {
      if ((await notificationsEnabled()) && (await askPermission())) {
        // renova o registro do push a cada abertura (token do aparelho pode mudar)
        registerPush();
      }
      run();
    })();

    const start = () => {
      if (!timer) timer = setInterval(run, POLL_MS);
    };
    const stop = () => {
      if (timer) clearInterval(timer);
      timer = null;
    };
    start();

    const sub = AppState.addEventListener('change', (st) => {
      if (st === 'active') {
        run();
        start();
      } else if (st === 'background') {
        // dá uma última olhada antes do iOS pausar o app
        run();
        stop();
      }
    });

    // Tocar no banner abre a central de avisos
    const tapSub = Notifications.addNotificationResponseReceivedListener(() => {
      router.push('/avisos');
    });

    return () => {
      stop();
      sub.remove();
      tapSub.remove();
    };
  }, [active]);
}
