# Stratton — app Android

App nativo (Kotlin + Jetpack Compose) que usa a API pública da StrattonPay
(`https://app.strattonpay.com.br/api/v1`).

## O que o app faz

| Aba | Recursos |
| --- | --- |
| **Início** | Saldo disponível, total e reservado · atalhos · últimas vendas · suas taxas |
| **Cobrar** | Gera cobrança Pix com QR Code e copia e cola · botões Copiar/Enviar · a tela atualiza sozinha quando o Pix é pago |
| **Vendas** | Lista com filtros (pagas, pendentes, estornadas…) · detalhe da venda com pagador, datas, end-to-end |
| **Saques** | Saque via chave Pix (com simulação de taxa) · pagar QR Code copia e cola · lista, detalhe, histórico de falhas e cancelamento |
| **Extrato** | Lançamentos do razão (entradas e saídas) com link para a venda/saque |

Notificações (ícone de engrenagem na tela Início → Configurações):
- Vendas pagas, saques pagos/recusados e contestações (MED, pré-chargeback, chargeback).
- Funcionam com o app fechado: o celular checa a StrattonPay a cada 2, 5, 15 ou 30 min (você escolhe).
- Tocar na notificação abre a venda ou o saque.

Login:
- Opção "Salvar Client ID e Secret neste celular" — ao sair, os dados ficam salvos (criptografados) e já aparecem preenchidos.
- "Esquecer dados salvos" apaga tudo do aparelho.

Segurança:
- A chave de API fica criptografada no aparelho (Android Keystore / EncryptedSharedPreferences).
- O app pede digital, rosto ou senha do celular ao abrir e antes de cada saque, pagamento de QR ou cancelamento.
- Backup do Android desligado, para a chave não ir para a nuvem.

## Chave de API

No painel da StrattonPay: **Configurações → Chaves de API**. Escopos para usar tudo:

| Escopo | Para quê |
| --- | --- |
| `TRANSACTIONS:WRITE` | Gerar cobranças e ver vendas |
| `BALANCE:READ` | Saldo e extrato |
| `COMPANY:READ` | Nome da empresa e taxas |
| `WITHDRAWS:WRITE` | Sacar, pagar QR Code e cancelar saques |

Se não quiser que o celular movimente dinheiro, crie a chave **sem** `WITHDRAWS:WRITE` (ou só com `WITHDRAWS:READ`).
As telas de saque vão mostrar o erro de permissão e o resto funciona normal.

## Como gerar o APK

### Opção 1 — GitHub Actions (sem instalar nada)
1. Suba esta pasta para um repositório no GitHub (branch `main`).
2. Abra a aba **Actions** → **Gerar APK**. Ele roda sozinho a cada push (ou clique em *Run workflow*).
3. Quando terminar, baixe o artefato **stratton-apk** e instale no celular.

### Opção 2 — Android Studio (Windows)
1. Instale o Android Studio e abra esta pasta (**File → Open**).
2. Espere o Gradle sincronizar.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. O arquivo sai em `app/build/outputs/apk/debug/app-debug.apk`.

No celular, permita "instalar apps de fontes desconhecidas" para o app que você usou para abrir o APK.

## Personalizar
- **Cores:** `app/src/main/java/br/com/strattonpay/app/ui/Theme.kt` (dourado `Gold`, fundo `Bg`).
- **Ícone:** `app/src/main/res/drawable/ic_launcher_foreground.xml` (hoje é um "S" provisório).
- **Nome:** `app/src/main/res/values/strings.xml`.
- **Domínio próprio:** na tela de login, em *Opções avançadas*, troque a URL da API.

## Para publicar na Play Store depois
O APK de debug serve para instalar direto. Para a loja, é preciso criar uma chave de assinatura
(`keystore`), configurar o `signingConfig` de release e gerar um `.aab` com `./gradlew bundleRelease`.

## Estrutura
```
app/src/main/java/br/com/strattonpay/app/
├── MainActivity.kt          bloqueio por digital/senha
├── notify/                  notificações em segundo plano (WorkManager)
├── data/
│   ├── StrattonApi.kt       cliente HTTP (Basic auth, idempotência, erros em português)
│   ├── Models.kt            modelos da API
│   └── CredentialStore.kt   chave criptografada
└── ui/
    ├── AppNav.kt            abas e navegação
    ├── LoginScreen.kt       login + tela de bloqueio
    ├── HomeScreen.kt        início
    ├── ChargeScreen.kt      cobrar Pix
    ├── TransactionsScreen.kt vendas
    ├── WithdrawScreens.kt   saques + pagar QR
    ├── StatementScreen.kt   extrato
    ├── SettingsScreen.kt    notificações e login salvo
    ├── Components.kt / Format.kt / Theme.kt
```
