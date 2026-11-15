import { A, BRAND } from './brand';
export const C = {
  bg: '#000000',
  card: '#0F0F12',
  card2: '#17171C',
  card3: '#202027',
  border: 'rgba(255,255,255,0.08)',
  borderStrong: 'rgba(255,255,255,0.14)',
  text: '#FFFFFF',
  sub: '#C7C7CC',
  muted: '#8E8E93',
  dim: '#55555C',
  red: BRAND.accent, // cor de destaque da marca (vermelho na Connect, roxo na Stratton)
  redLight: BRAND.accentLight,
  redDeep: BRAND.accentDeep,
  redSoft: A(0.15),
  green: '#30D158',
  greenSoft: 'rgba(48,209,88,0.14)',
  orange: '#FF9F0A',
  orangeSoft: 'rgba(255,159,10,0.14)',
  blue: '#0A84FF',
  silver: '#D9D9DE',
  danger: '#FF453A',
};

export const R = { sm: 12, md: 18, lg: 24, xl: 30 };

// Preenche a tela toda (StyleSheet.absoluteFillObject foi removido nas versões novas do React Native)
export const FILL = { position: 'absolute' as const, top: 0, left: 0, right: 0, bottom: 0 };
