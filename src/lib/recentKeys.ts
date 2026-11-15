import * as SecureStore from 'expo-secure-store';
import { api, PixKeyType } from './api';

export type RecentKey = { key: string; type: PixKeyType; at: number };

const STORE = 'cp_recent_pix_keys';
const MAX = 12;

async function readLocal(): Promise<RecentKey[]> {
  try {
    const raw = await SecureStore.getItemAsync(STORE);
    return raw ? (JSON.parse(raw) as RecentKey[]) : [];
  } catch {
    return [];
  }
}

/** Guarda a chave usada num envio, igual às "Chaves recentes" do painel */
export async function rememberKey(key: string, type: PixKeyType) {
  const list = (await readLocal()).filter((k) => k.key !== key);
  list.unshift({ key, type, at: Date.now() });
  await SecureStore.setItemAsync(STORE, JSON.stringify(list.slice(0, MAX))).catch(() => {});
}

export async function forgetKey(key: string) {
  const list = (await readLocal()).filter((k) => k.key !== key);
  await SecureStore.setItemAsync(STORE, JSON.stringify(list)).catch(() => {});
  // também esconde chaves que vêm do histórico do servidor
  const hidden = await hiddenKeys();
  hidden.add(key);
  await SecureStore.setItemAsync(STORE + '_hidden', JSON.stringify([...hidden])).catch(() => {});
}

async function hiddenKeys(): Promise<Set<string>> {
  try {
    const raw = await SecureStore.getItemAsync(STORE + '_hidden');
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

/** Chaves recentes: as salvas neste iPhone + as dos últimos saques da conta (mesma lista que o painel mostra) */
export async function loadRecentKeys(): Promise<RecentKey[]> {
  const [local, hidden, remote] = await Promise.all([
    readLocal(),
    hiddenKeys(),
    api
      .withdraws({ perPage: 40 })
      .then((p) => p.items)
      .catch(() => []),
  ]);
  const out: RecentKey[] = [];
  const seen = new Set<string>();
  const push = (k: RecentKey) => {
    if (!k.key || seen.has(k.key) || hidden.has(k.key)) return;
    seen.add(k.key);
    out.push(k);
  };
  local.forEach(push);
  remote
    .filter((w) => w.pix_key && w.pix_key_type && w.destination_type !== 'QR_CODE' && w.destination_type !== 'CRYPTO')
    .forEach((w) =>
      push({ key: w.pix_key as string, type: w.pix_key_type as PixKeyType, at: new Date(w.created_at ?? w.createdAt ?? 0).getTime() })
    );
  return out.slice(0, MAX);
}
