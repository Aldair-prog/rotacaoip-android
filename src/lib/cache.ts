// Guarda o QR Code recém-criado em memória para abrir a tela da cobrança na hora,
// sem esperar uma nova consulta à API.
export const chargeCache = new Map<string, { code: string; image: string; amount: number }>();
