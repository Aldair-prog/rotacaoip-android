import type { PixKeyType } from './api';

export const KEY_TYPES: { type: PixKeyType; label: string }[] = [
  { type: 'CPF', label: 'CPF' },
  { type: 'CNPJ', label: 'CNPJ' },
  { type: 'EMAIL', label: 'E-mail' },
  { type: 'PHONE', label: 'Celular' },
  { type: 'EVP', label: 'Aleatória' },
];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function isCPF(v: string) {
  const c = v.replace(/\D/g, '');
  if (c.length !== 11 || /^(\d)\1+$/.test(c)) return false;
  const calc = (len: number) => {
    let s = 0;
    for (let i = 0; i < len; i++) s += Number(c[i]) * (len + 1 - i);
    const r = (s * 10) % 11;
    return r === 10 ? 0 : r;
  };
  return calc(9) === Number(c[9]) && calc(10) === Number(c[10]);
}

export function isCNPJ(v: string) {
  const c = v.replace(/\D/g, '');
  if (c.length !== 14 || /^(\d)\1+$/.test(c)) return false;
  const calc = (len: number) => {
    const w = len === 12 ? [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2] : [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
    const s = w.reduce((acc, n, i) => acc + n * Number(c[i]), 0);
    const r = s % 11;
    return r < 2 ? 0 : 11 - r;
  };
  return calc(12) === Number(c[12]) && calc(13) === Number(c[13]);
}

/** Tenta adivinhar o tipo da chave a partir do que foi digitado/colado. */
export function detectKeyType(raw: string): PixKeyType | null {
  const v = raw.trim();
  if (!v) return null;
  if (v.includes('@')) return 'EMAIL';
  if (UUID.test(v)) return 'EVP';
  const d = v.replace(/\D/g, '');
  if (v.startsWith('+')) return 'PHONE';
  if (d.length === 14) return 'CNPJ';
  if (d.length === 11) return isCPF(d) ? 'CPF' : 'PHONE';
  if (d.length === 10 || ((d.length === 12 || d.length === 13) && d.startsWith('55'))) return 'PHONE';
  return null;
}

export function isValidKey(raw: string, type: PixKeyType | null) {
  const v = raw.trim();
  const d = v.replace(/\D/g, '');
  switch (type) {
    case 'CPF':
      return isCPF(d);
    case 'CNPJ':
      return isCNPJ(d);
    case 'EMAIL':
      return EMAIL.test(v);
    case 'EVP':
      return UUID.test(v);
    case 'PHONE':
      return d.length >= 10 && d.length <= 13;
    default:
      return false;
  }
}

/** Formato que vai pra API. */
export function normalizeKey(raw: string, type: PixKeyType) {
  const v = raw.trim();
  const d = v.replace(/\D/g, '');
  if (type === 'CPF' || type === 'CNPJ') return d;
  if (type === 'EMAIL') return v.toLowerCase();
  if (type === 'PHONE') return d.startsWith('55') && d.length >= 12 ? `+${d}` : `+55${d}`;
  return v.toLowerCase();
}

/** Formato bonito pra mostrar na tela. */
export function displayKey(raw: string, type: PixKeyType | null) {
  const d = raw.replace(/\D/g, '');
  if (type === 'CPF' && d.length === 11) return d.replace(/(\d{3})(\d{3})(\d{3})(\d{2})/, '$1.$2.$3-$4');
  if (type === 'CNPJ' && d.length === 14) return d.replace(/(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})/, '$1.$2.$3/$4-$5');
  if (type === 'PHONE') {
    const n = d.startsWith('55') && d.length >= 12 ? d.slice(2) : d;
    if (n.length === 11) return n.replace(/(\d{2})(\d{5})(\d{4})/, '($1) $2-$3');
    if (n.length === 10) return n.replace(/(\d{2})(\d{4})(\d{4})/, '($1) $2-$3');
  }
  return raw.trim();
}

export const keyLabel = (t?: PixKeyType | null) => KEY_TYPES.find((k) => k.type === t)?.label ?? 'Chave';

// ---------- Pix Copia e Cola (BR Code / EMV) ----------

export function isBrCode(v: string) {
  const s = v.trim();
  return s.startsWith('000201') && s.length >= 50;
}

function parseTLV(s: string) {
  const out: Record<string, string> = {};
  let i = 0;
  while (i + 4 <= s.length) {
    const tag = s.slice(i, i + 2);
    const len = parseInt(s.slice(i + 2, i + 4), 10);
    if (Number.isNaN(len)) break;
    out[tag] = s.slice(i + 4, i + 4 + len);
    i += 4 + len;
  }
  return out;
}

export type BrCodeInfo = { amount: number | null; merchant: string | null; city: string | null; key: string | null };

export function parseBrCode(code: string): BrCodeInfo {
  try {
    const t = parseTLV(code.trim());
    const acc = t['26'] ? parseTLV(t['26']) : {};
    const amount = t['54'] ? Math.round(parseFloat(t['54']) * 100) : null;
    return {
      amount: amount && amount > 0 ? amount : null,
      merchant: t['59']?.trim() || null,
      city: t['60']?.trim() || null,
      key: acc['01'] ?? null,
    };
  } catch {
    return { amount: null, merchant: null, city: null, key: null };
  }
}
