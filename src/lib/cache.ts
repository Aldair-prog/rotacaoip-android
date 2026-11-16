// Guarda o QR Code recém-criado em memória para abrir a tela da cobrança na hora,
// sem esperar uma nova consulta à API.
export const chargeCache = new Map<string, { code: string; image: string; amount: number }>();

// Última versão de cada tela, em memória (some ao fechar o app; nada vai para o disco).
// Ao voltar para uma aba, ela aparece na hora e atualiza por trás.
export const screenCache = new Map<string, unknown>();
