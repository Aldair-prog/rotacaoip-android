// Cliente da API do painel (Connect Pay / STRATTON).
// Login igual ao painel: e-mail + senha (+ código do autenticador) → token Bearer.
// Os dados (saldo, transações, saques, empresa) usam os mesmos endpoints /api/v1 do painel.
import { BRAND, PANEL_URL } from '../brand';

export const API_BASE = `${PANEL_URL}/api/v1`;

export type Session = { accessToken: string; refreshToken: string | null; expiresAt: string | null };

let session: Session | null = null;
let device = { id: 'ios-app', fingerprint: 'ios-app' };
let onSessionChange: ((s: Session | null) => void) | null = null;

export function setSession(s: Session | null) {
  session = s;
}
export function getSession() {
  return session;
}
export function setDevice(id: string, fingerprint: string) {
  device = { id, fingerprint };
}
export function getDevice() {
  return device;
}
/** O painel web (aberto no app) pode renovar o token; o app passa a usar o mais novo */
export function adoptRefreshToken(rt: string, accessToken?: string | null) {
  if (!session || !rt) return;
  if (rt === session.refreshToken && (!accessToken || accessToken === session.accessToken)) return;
  session = { ...session, refreshToken: rt, accessToken: accessToken || session.accessToken };
  onSessionChange?.(session);
}
/** O app avisa a camada de sessão quando o token é renovado ou perdido */
export function onAuthChange(cb: (s: Session | null) => void) {
  onSessionChange = cb;
}

export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(message: string, status: number, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

type Query = Record<string, string | number | undefined | null>;

function qs(q?: Query) {
  if (!q) return '';
  const parts = Object.entries(q)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

function friendly(status: number, data: any, path: string): string {
  const raw = data?.message ?? data?.error ?? data?.detail;
  const msg = Array.isArray(raw) ? raw.join('\n') : typeof raw === 'string' ? raw : '';
  if (path.startsWith('/auth/signin/otp')) return status === 400 || status === 401 || status === 403 ? 'Código inválido ou expirado. Tente de novo.' : msg || 'Não foi possível verificar o código.';
  if (path.startsWith('/auth/signin')) return status === 401 || status === 400 ? 'E-mail ou senha incorretos.' : status === 429 ? 'Muitas tentativas. Aguarde um pouco e tente de novo.' : msg || 'Não foi possível entrar.';
  if (status === 401) return 'Sua sessão expirou. Entre de novo.';
  if (status === 403) return msg && msg !== 'Forbidden' ? msg : 'Você não tem permissão pra fazer isso.';
  if (status === 429) return 'Muitas tentativas. Aguarde alguns segundos e tente de novo.';
  if (status === 409) return msg || 'Não foi possível concluir: saldo insuficiente ou operação repetida.';
  if (status >= 500) return `A ${BRAND.name} está instável agora. Tente de novo em instantes.`;
  if (msg === 'Validation failed') return 'Confira os dados informados.';
  return msg || `Algo deu errado (erro ${status}).`;
}

function baseHeaders(): Record<string, string> {
  return { Accept: 'application/json', 'x-device-id': device.id, 'x-device-fingerprint': device.fingerprint };
}

async function raw(path: string, init: RequestInit): Promise<{ res: Response; data: any }> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, init);
  } catch {
    throw new ApiError('Sem conexão com a internet.', 0);
  }
  const text = await res.text();
  let data: any = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  return { res, data };
}

function parseSession(d: any): Session & { twoFactor: boolean } {
  return {
    accessToken: d?.access_token ?? d?.accessToken,
    refreshToken: d?.refresh_token ?? d?.refreshToken ?? null,
    expiresAt: d?.expires_at ?? d?.expiresAt ?? null,
    twoFactor: !!(d?.two_factor_authentication_enabled ?? d?.twoFactorAuthenticationEnabled),
  };
}

let refreshing: Promise<boolean> | null = null;
let sharedWithPanel = false; // Painel Admin aberto: ele também renova a mesma sessão

/** O Painel Admin (WebView) avisa quando abre/fecha, pra o app não brigar com ele pela sessão */
export function setPanelOpen(open: boolean) {
  sharedWithPanel = open;
}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function refresh(): Promise<boolean> {
  if (!session?.refreshToken) return false;
  if (!refreshing) {
    refreshing = (async () => {
      const tried = session!.refreshToken;
      const { res, data } = await raw('/auth/refresh', {
        method: 'POST',
        headers: { ...baseHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: tried }),
      });
      if (!res.ok) {
        // O painel pode ter renovado a sessão no mesmo instante: espera o token novo chegar e tenta com ele
        for (let i = 0; i < 6 && session && session.refreshToken === tried; i++) await wait(500);
        if (!session?.refreshToken || session.refreshToken === tried) return false;
        const again = await raw('/auth/refresh', {
          method: 'POST',
          headers: { ...baseHeaders(), 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: session.refreshToken }),
        });
        if (!again.res.ok) return false;
        const s2 = parseSession(again.data);
        if (!s2.accessToken) return false;
        session = { accessToken: s2.accessToken, refreshToken: s2.refreshToken ?? session.refreshToken, expiresAt: s2.expiresAt };
        onSessionChange?.(session);
        return true;
      }
      const s = parseSession(data);
      if (!s.accessToken) return false;
      session = { accessToken: s.accessToken, refreshToken: s.refreshToken ?? session!.refreshToken, expiresAt: s.expiresAt };
      onSessionChange?.(session);
      return true;
    })()
      .catch(() => false)
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

async function req<T>(
  path: string,
  opts: { method?: 'GET' | 'POST' | 'DELETE'; body?: unknown; query?: Query; idempotencyKey?: string; retried?: boolean } = {}
): Promise<T> {
  if (!session) throw new ApiError('Você não está conectado.', 401);
  const headers: Record<string, string> = { ...baseHeaders(), Authorization: `Bearer ${session.accessToken}` };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (opts.idempotencyKey) headers['X-Idempotency-Key'] = opts.idempotencyKey;

  const { res, data } = await raw(`${path}${qs(opts.query)}`, {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });

  if (res.status === 401 && !opts.retried) {
    if (await refresh()) return req<T>(path, { ...opts, retried: true });
    // Com o painel aberto, não derruba a sessão do app: ele vai sincronizar o token ao fechar
    if (!sharedWithPanel) {
      session = null;
      onSessionChange?.(null);
    }
  }
  if (!res.ok) throw new ApiError(friendly(res.status, data, path), res.status, data?.code ?? data?.name);
  return data as T;
}

// ---------- Login (mesmo fluxo do painel) ----------

export type SignInResult = { kind: 'ok'; session: Session } | { kind: 'otp'; otpToken: string };

export const auth = {
  async signIn(email: string, password: string): Promise<SignInResult> {
    const path = '/auth/signin';
    const { res, data } = await raw(path, {
      method: 'POST',
      headers: { ...baseHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.trim().toLowerCase(), password }),
    });
    if (!res.ok) throw new ApiError(friendly(res.status, data, path), res.status);
    const s = parseSession(data);
    if (!s.accessToken) throw new ApiError('Resposta inesperada do servidor.', res.status);
    if (s.twoFactor) return { kind: 'otp', otpToken: s.accessToken };
    return { kind: 'ok', session: { accessToken: s.accessToken, refreshToken: s.refreshToken, expiresAt: s.expiresAt } };
  },

  async verifyOtp(code: string, otpToken: string): Promise<Session> {
    const path = '/auth/signin/otp';
    const { res, data } = await raw(path, {
      method: 'POST',
      headers: { ...baseHeaders(), 'Content-Type': 'application/json', Authorization: `Bearer ${otpToken}` },
      body: JSON.stringify({ code, otpSessionToken: otpToken }),
    });
    if (!res.ok) throw new ApiError(friendly(res.status, data, path), res.status);
    const s = parseSession(data);
    if (!s.accessToken) throw new ApiError('Resposta inesperada do servidor.', res.status);
    return { accessToken: s.accessToken, refreshToken: s.refreshToken, expiresAt: s.expiresAt };
  },

  /** Renova a sessão a partir do refresh token salvo (ao abrir o app) */
  async resume(refreshToken: string): Promise<Session | null> {
    session = { accessToken: '', refreshToken, expiresAt: null };
    const ok = await refresh();
    return ok ? session : null;
  },
};

// ---------- Tipos ----------

export type Fee = { available?: boolean; fixed: number | null; percentage: number | null };

export type Company = {
  id: string;
  name: string;
  trade_name: string | null;
  document: { number: string; type: 'CPF' | 'CNPJ' };
  status: string;
  withdraw_fee_fixed?: number | null;
  withdraw_fee_percentage?: number | null;
  fees?: {
    incoming?: { pix?: Fee; credit_card?: Fee; invoice?: Fee };
    outgoing?: { fixed: number; percentage: number };
  };
  created_at: string;
};

export type Balance = { total_balance: number; reserved_amount: number; available_balance: number };

export type TxStatus =
  | 'PROCESSING'
  | 'PENDING'
  | 'APPROVED'
  | 'REFUSED'
  | 'IN_PROTEST'
  | 'PRE_CHARGEBACK'
  | 'REFUNDED'
  | 'CANCELLED'
  | 'CHARGEBACK';

export type Transaction = {
  id: string;
  amount: number;
  status?: TxStatus;
  method?: 'PIX' | 'CREDIT_CARD' | 'INVOICE';
  created_at?: string;
  createdAt?: string;
  paid_at?: string | null;
  paidAt?: string | null;
  customer?: { name?: string; email?: string; document?: any } | null;
  payer?: { name?: string } | null;
  description?: string | null;
  pix?: any;
  refused_reason?: string | null;
  [k: string]: any;
};

export type PixCharge = {
  id: string;
  amount: number;
  externalId: string;
  qrcode: { code: string; base64: string };
};

export type WithdrawStatus = 'PENDING_ANALYSIS' | 'APPROVED' | 'COMPLETED' | 'REFUSED' | 'CANCELLED';
export type PixKeyType = 'CPF' | 'CNPJ' | 'EMAIL' | 'PHONE' | 'EVP';

export type Withdraw = {
  id: string;
  status: WithdrawStatus;
  amount: number;
  fee: number;
  net_amount: number;
  external_id?: string | null;
  failure_count?: number;
  failure_history?: { reason: string; created_at: string }[];
  destination_type?: 'PIX' | 'CRYPTO' | 'QR_CODE';
  pix_key?: string | null;
  pix_key_type?: PixKeyType | null;
  description?: string | null;
  reason?: string | null;
  origin?: string;
  created_at?: string;
  createdAt?: string;
  [k: string]: any;
};

export type FeePreview = { amount: number; fixed_fee: number; percent_fee: number; fee: number; net_amount: number };

export type Page<T> = { items: T[]; nextCursor: string | null };

export type Deposit = { transactionId: string; amount: number; qrcode: string; qrcodeBase64: string; expiresAt: string | null };

export type DecodedQr = {
  kind: string | null;
  amount: number | null;
  fee: number | null;
  totalAmount: number | null;
  receiverName: string | null;
  receiverCity: string | null;
  receiverDocument: string | null;
  pixKey: string | null;
};

export type PixPayment = {
  id: string;
  status: string;
  amount: number;
  fee: number;
  total_amount: number;
  receiver_name?: string | null;
  paid_at?: string | null;
  created_at: string;
};

export type MovementKind =
  | 'SALE'
  | 'REFUND'
  | 'CHARGEBACK'
  | 'PRE_CHARGEBACK'
  | 'RESERVE_RELEASE'
  | 'SPLIT'
  | 'AFFILIATE_COMMISSION'
  | 'ADJUSTMENT'
  | 'WITHDRAW'
  | 'ANTICIPATION'
  | 'OTHER';

export type Movement = {
  id: string;
  kind: MovementKind;
  occurred_at: string;
  gross_amount: number;
  fee_amount: number;
  reserve_amount?: number;
  split_amount?: number;
  net_amount: number;
  description: string | null;
  counterparty_name?: string | null;
  payment_method?: string | null;
  transaction_id?: string | null;
  withdraw_id?: string | null;
  withdraw_status?: WithdrawStatus | null;
  withdraw_destination_type?: string | null;
  withdraw_pix_key?: string | null;
};

function page<T>(r: any): Page<T> {
  const items: T[] = r?.items ?? r?.data ?? [];
  const next =
    r?.meta?.nextCursor ?? r?.meta?.next_cursor ?? r?.pagination?.next_cursor ?? r?.pagination?.nextCursor ?? null;
  return { items, nextCursor: next };
}

// ---------- Endpoints ----------

export type TenantSettings = {
  tenant_name?: string;
  slogan?: string;
  terms_url?: string | null;
  privacy_url?: string | null;
  support_enabled?: boolean;
  whatsapp_number?: string | null;
  instagram_handle?: string | null;
};

/** Monta a URL completa de um arquivo do painel (ex.: PDF de termos) */
export function fileUrl(path?: string | null) {
  if (!path) return null;
  if (path.startsWith('http')) return path;
  return `${PANEL_URL}/api${path.startsWith('/') ? '' : '/'}${path}`;
}

export const api = {
  settings: async (): Promise<TenantSettings> => {
    const { res, data } = await raw('/settings', { headers: baseHeaders() });
    if (!res.ok) throw new ApiError('Não foi possível carregar as configurações.', res.status);
    return data;
  },
  company: () => req<Company>('/companies/@me'),
  me: () => req<any>('/users/@me'),
  notifications: async (perPage = 10): Promise<AppNotification[]> => {
    const r = await req<any>('/notifications/@me', { query: { perPage, per_page: perPage } });
    return r?.items ?? r?.data ?? [];
  },
  announcements: async (): Promise<AppNotification[]> => {
    const r = await req<any>('/notifications/@me/announcements');
    return Array.isArray(r) ? r : r?.items ?? r?.data ?? [];
  },
  // Regras de saque do painel (Configurações → Saques): mínimo, máximo e intervalo por envio
  withdrawRules: async (): Promise<WithdrawRules | null> => {
    const r = await req<any>('/settings/admin');
    const t = r?.gateway_settings?.transfers;
    if (!t) return null;
    return {
      enabled: t.enabled !== false,
      min: typeof t.minPerTransfer === 'number' ? t.minPerTransfer : null,
      max: typeof t.maxPerTransfer === 'number' && t.maxPerTransfer > 0 ? t.maxPerTransfer : null,
      delayMinutes: t.delayMinutes ?? null,
      dailyLimit: t.dailyLimitEnabled && t.dailyLimit > 0 ? t.dailyLimit : null,
    };
  },
  markAllRead: () => req<any>('/notifications/@me/read', { method: 'POST' }),
  markRead: (id: string) => req<any>(`/notifications/@me/${id}/read`, { method: 'POST' }),

  /** Resumo do dia (entradas, saídas, vendas) a partir do extrato real */
  today: async (): Promise<DaySummary> => {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    const out: DaySummary = { incoming: 0, outgoing: 0, sales: 0, fees: 0 };
    let cursor: string | null = null;
    for (let i = 0; i < 5; i++) {
      const pg: { items: Movement[]; nextCursor: string | null } = await api.movements({ cursor, perPage: 50 });
      let older = false;
      for (const m of pg.items) {
        if (new Date(m.occurred_at) < start) {
          older = true;
          continue;
        }
        if (m.net_amount > 0) out.incoming += m.net_amount;
        else out.outgoing += -m.net_amount;
        if (m.kind === 'SALE') out.sales += 1;
        out.fees += m.fee_amount || 0;
      }
      if (older || !pg.nextCursor) break;
      cursor = pg.nextCursor;
    }
    return out;
  },
  notificationsSummary: () => req<{ unread: number }>('/notifications/@me/summary'),
  // Push do painel (o mesmo botão "Notificações push neste dispositivo")
  pushSubscribe: (sub: { endpoint: string; publicKey: string; authenticationSecret: string }) =>
    req<any>('/notifications/@me/push-subscriptions', { method: 'POST', body: sub }),
  pushUnsubscribe: (endpoint: string) =>
    req<any>('/notifications/@me/push-subscriptions', { method: 'DELETE', body: { endpoint } }),
  balance: () => req<Balance>('/withdraws/@me/balance'),

  transactions: async (q: { cursor?: string | null; perPage?: number; status?: string; method?: string } = {}) =>
    page<Transaction>(await req('/transactions', { query: { perPage: q.perPage ?? 20, cursor: q.cursor, status: q.status, method: q.method } })),
  transaction: (id: string) => req<Transaction>(`/transactions/${id}`),

  createPixCharge: (
    p: { amount: number; document: { type: 'CPF' | 'CNPJ'; number: string }; name?: string; description?: string; expiresIn?: number },
    idempotencyKey: string
  ) =>
    req<PixCharge>('/transactions', {
      method: 'POST',
      idempotencyKey,
      body: {
        method: 'PIX',
        amount: p.amount,
        payer: { document: p.document, name: p.name || undefined },
        description: p.description || undefined,
        expiresIn: p.expiresIn,
        metadata: { origin: 'ios-app' },
      },
    }),

  feePreview: (amount: number) => req<FeePreview>('/withdraws/@me/fee-preview', { query: { amount } }),

  // ---- Rotas do painel (sessão do usuário): as mesmas que o site usa ----
  /** Gera um Pix pra receber (o "Depositar/Receber" do painel) */
  deposit: async (amount: number, idempotencyKey: string): Promise<Deposit> => {
    const d = await req<any>('/deposits/@me', { method: 'POST', body: { amount }, idempotencyKey });
    return {
      transactionId: d?.transaction_id ?? d?.transactionId ?? d?.id,
      amount: d?.amount ?? amount,
      qrcode: d?.qrcode ?? d?.qr_code ?? '',
      qrcodeBase64: d?.qrcode_base64 ?? d?.qrcodeBase64 ?? '',
      expiresAt: d?.expires_at ?? d?.expiresAt ?? null,
    };
  },
  depositLimits: () => req<{ min_deposit_amount?: number | null; max_deposit_amount?: number | null }>('/deposits/@me/limits'),

  /** Lê um Pix copia e cola antes de pagar (recebedor, valor, taxa) */
  decodeQr: async (qrcode: string): Promise<DecodedQr> => {
    const d = await req<any>('/payments/@me/decode', { method: 'POST', body: { qrcode } });
    return {
      kind: d?.kind ?? null,
      amount: d?.amount ?? null,
      fee: d?.fee ?? null,
      totalAmount: d?.total_amount ?? null,
      receiverName: d?.receiver_name ?? null,
      receiverCity: d?.receiver_city ?? null,
      receiverDocument: d?.receiver_document ?? null,
      pixKey: d?.pix_key ?? null,
    };
  },
  /** Paga um Pix copia e cola com o saldo (mesma rota do painel) */
  payPix: (p: { qrcode: string; amount?: number }, idempotencyKey: string) =>
    req<PixPayment>('/payments/@me', { method: 'POST', body: p, idempotencyKey }),

  /** Extrato do painel: tudo que entrou e saiu (vendas, saques, estornos...) */
  movements: async (q: { cursor?: string | null; perPage?: number; kind?: string } = {}) => {
    const r = await req<any>('/statement/@me/movements', { query: { perPage: q.perPage ?? 20, cursor: q.cursor, kind: q.kind || undefined } });
    const items: Movement[] = r?.data ?? r?.items ?? r?.movements ?? [];
    const next = r?.next_cursor ?? r?.nextCursor ?? r?.pagination?.next_cursor ?? r?.pagination?.nextCursor ?? r?.meta?.nextCursor ?? null;
    const hasMore = r?.has_more ?? r?.hasMore ?? r?.pagination?.has_more ?? !!next;
    return { items, nextCursor: hasMore ? next : null } as Page<Movement>;
  },

  withdraw: (p: { pixKeyType: PixKeyType; pixKey: string; amount: number; description?: string }, idempotencyKey: string) =>
    req<Withdraw>('/withdraws/@me', { method: 'POST', idempotencyKey, body: { ...p, description: p.description || undefined } }),

  payQrCode: (p: { qrcode: string; amount?: number }, idempotencyKey: string) =>
    req<Withdraw>('/withdraws/@me/qr-code', { method: 'POST', idempotencyKey, body: p }),

  withdraws: async (q: { cursor?: string | null; perPage?: number; status?: string } = {}) =>
    page<Withdraw>(await req('/withdraws/@me', { query: { perPage: q.perPage ?? 20, cursor: q.cursor, status: q.status } })),
  withdrawById: (id: string) => req<Withdraw>(`/withdraws/@me/${id}`),
  cancelWithdraw: (id: string) => req<Withdraw>(`/withdraws/@me/${id}/cancel`, { method: 'POST', body: {} }),
};

// Taxa calculada localmente a partir de uma única consulta (como a doc recomenda)
export function calcFee(amount: number, fixed: number, percentBp: number) {
  return fixed + Math.floor((amount * percentBp) / 10000);
}

export type AppNotification = {
  id: string;
  category: 'INCOMING' | 'OUTGOING' | string;
  title: string;
  message: string;
  read_at: string | null;
  created_at: string;
  announcement_id?: string | null;
  banner_url?: string | null;
};

export type DaySummary = { incoming: number; outgoing: number; sales: number; fees: number };

export type WithdrawRules = {
  enabled: boolean;
  min: number | null;
  max: number | null;
  delayMinutes: number | null;
  dailyLimit: number | null;
};
