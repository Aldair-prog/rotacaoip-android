import { Platform } from 'react-native';
import Constants from 'expo-constants';

// Marca do app (definida na hora de gerar o app, em app.config.js → extra.brand)
export type BrandId = 'connect' | 'stratton';
export const BRAND_ID: BrandId = (Constants.expoConfig?.extra as any)?.brand === 'stratton' ? 'stratton' : 'connect';

type Brand = {
  id: BrandId;
  name: string;
  /** Nome curto em maiúsculas, para selos ("CONTA CONNECT") */
  short: string;
  host: string;
  site: string;
  contact: string;
  slogan: string;
  copyright: string;
  sound: string;
  /** Cor de destaque e versões dela */
  rgb: [number, number, number];
  accent: string;
  accentLight: string;
  accentDeep: string;
  /** Segunda cor do degradê do avatar no login */
  accent2: string;
  /** Degradê do cartão de saldo (escuro → preto) */
  cardGradient: [string, string, string];
  /** Fundo de item não lido / destaque escuro */
  tint: string;
  /** SHA-256 do e-mail com acesso ao Painel Admin pelo app (o e-mail não fica no código) */
  adminHash: string | null;
};

const BRANDS: Record<BrandId, Brand> = {
  connect: {
    id: 'connect',
    name: 'Connect Pay',
    short: 'CONNECT',
    host: 'app.connectmdcpay.com.br',
    site: 'https://www.connectmdcpay.com.br',
    contact: 'https://www.connectmdcpay.com.br/#contato',
    slogan: 'Connect Pay · Pagamentos sem limites',
    copyright: 'OPERAÇÕES DIGITAIS - CONNECT PAY',
    sound: 'connect_notificacao.wav',
    rgb: [244, 33, 46],
    accent: '#F4212E',
    accentLight: '#FF4A55',
    accentDeep: '#5C060B',
    accent2: '#7B61FF',
    cardGradient: ['#3A0509', '#1A0A0C', '#0D0D10'],
    tint: '#160C0E',
    adminHash: '1bde2839dd3691d02907deea24bf72207fc878aeae12c5532cba87a40e7e482f',
  },
  stratton: {
    id: 'stratton',
    name: 'STRATTON',
    short: 'STRATTON',
    host: 'app.strattonpay.com.br',
    site: 'https://www.strattonpay.com.br',
    contact: 'https://www.strattonpay.com.br',
    slogan: 'STRATTON · Pagamentos sem limites',
    copyright: 'OPERAÇÕES DIGITAIS - STRATTON',
    sound: 'stratton_notificacao.wav',
    rgb: [139, 61, 255],
    accent: '#8B3DFF',
    accentLight: '#A66BFF',
    accentDeep: '#2A0C5C',
    accent2: '#E040FB',
    cardGradient: ['#24093F', '#130A1F', '#0D0D10'],
    tint: '#120C18',
    adminHash: null,
  },
};

export const BRAND = BRANDS[BRAND_ID];

/** Cor da marca com transparência: A(0.3) → rgba(...,0.3) */
export const A = (alpha: number) => `rgba(${BRAND.rgb[0]},${BRAND.rgb[1]},${BRAND.rgb[2]},${alpha})`;

export const PANEL_URL = `https://${BRAND.host}`;

export const LOGO = BRAND_ID === 'stratton' ? require('../brands/stratton/logo.png') : require('../brands/connect/logo.png');
/** Proporção largura/altura da logo */
export const LOGO_RATIO = BRAND_ID === 'stratton' ? 360 / 370 : 512 / 469;

/** Onde ficam as permissões do app no aparelho (texto para orientar o cliente) */
export const SETTINGS_PATH =
  Platform.OS === 'ios' ? `Ajustes do iPhone → ${BRAND.name}` : `Configurações → Apps → ${BRAND.name}`;
export const DEVICE = Platform.OS === 'ios' ? 'iPhone' : 'celular';

/** Ícone da biometria: Face ID no iPhone, digital no Android */
export const BIO_ICON = (Platform.OS === 'ios' ? 'scan' : 'finger-print') as 'scan' | 'finger-print';
