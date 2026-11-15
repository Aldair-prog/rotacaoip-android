export function brl(cents: number | null | undefined) {
  const v = (cents ?? 0) / 100;
  const neg = v < 0;
  const [i, d] = Math.abs(v).toFixed(2).split('.');
  const int = i.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${neg ? '-' : ''}R$ ${int},${d}`;
}

export function percent(bp: number | null | undefined) {
  return `${((bp ?? 0) / 100).toFixed(2).replace('.', ',')}%`;
}

export function feeText(fixed: number | null | undefined, bp: number | null | undefined) {
  const f = fixed ?? 0;
  const p = bp ?? 0;
  if (!f && !p) return 'Grátis';
  if (f && p) return `${percent(p)} + ${brl(f)}`;
  return f ? brl(f) : percent(p);
}

const pad = (n: number) => String(n).padStart(2, '0');

function startOfDay(d: Date) {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

export function dayLabel(iso?: string | null) {
  if (!iso) return '';
  const d = new Date(iso);
  const diff = Math.round((startOfDay(new Date()) - startOfDay(d)) / 86400000);
  if (diff === 0) return 'Hoje';
  if (diff === 1) return 'Ontem';
  const meses = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];
  return `${d.getDate()} de ${meses[d.getMonth()]}${d.getFullYear() !== new Date().getFullYear() ? ` de ${d.getFullYear()}` : ''}`;
}

export function timeLabel(iso?: string | null) {
  if (!iso) return '';
  const d = new Date(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function dateTime(iso?: string | null) {
  if (!iso) return '—';
  const d = new Date(iso);
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} às ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function maskDoc(n?: string | null) {
  const d = (n ?? '').replace(/\D/g, '');
  if (d.length === 11) return d.replace(/(\d{3})(\d{3})(\d{3})(\d{2})/, '$1.$2.$3-$4');
  if (d.length === 14) return d.replace(/(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})/, '$1.$2.$3/$4-$5');
  return n ?? '';
}

export function greeting() {
  const h = new Date().getHours();
  if (h < 5) return 'Boa noite';
  if (h < 12) return 'Bom dia';
  if (h < 18) return 'Boa tarde';
  return 'Boa noite';
}

export const txDate = (t: { created_at?: string; createdAt?: string }) => t.created_at ?? t.createdAt ?? '';
