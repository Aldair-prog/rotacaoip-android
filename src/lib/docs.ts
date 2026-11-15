import { router } from 'expo-router';

// Páginas abertas pelo próprio app (termos, PDFs, links do painel). Um link vindo de fora
// (outro app, site, mensagem) não entra nesta lista, então não consegue abrir página falsa dentro do app.
const trusted = new Set<string>();

export function openDoc(url: string, title: string) {
  trusted.add(url);
  router.push({ pathname: '/documento', params: { url, title } });
}

export const isTrusted = (url: string) => trusted.has(url);
