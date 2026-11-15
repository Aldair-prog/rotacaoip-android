# STRATTON e Connect Pay: um código só, 4 apps

Este projeto (Expo / React Native) gera os quatro apps:

| App | Android | iPhone |
|---|---|---|
| Connect Pay | `br.com.connectmdcpay.app` | `br.com.connectmdcpay.app` |
| STRATTON | `br.com.strattonpay.app` | `br.com.strattonpay.app` |

A marca é escolhida pela variável `APP_BRAND` (`connect` ou `stratton`).
Tudo que muda de uma marca para a outra fica em dois lugares:

- `app.config.js`: nome, ícones, pacote, som da notificação
- `src/brand.ts`: cores, endereço do painel, textos, direitos autorais e acesso ao Painel Admin
- `brands/<marca>/`: logo, ícone, splash, ícone da notificação e som

## Android
A cada envio para a branch `stratton-app`, o GitHub Actions gera `Stratton.apk` e `ConnectPay.apk`
(release `stratton-build-N`), já com a versão maior que a anterior.
As notificações no Android funcionam sem Firebase e sem Client ID/Secret: o app entrega ao painel uma
assinatura Web Push própria (módulo `modules/app-push`).

## iPhone
1. Conta Apple Developer (empresa) e o app criado no App Store Connect com o pacote da tabela.
2. `npx eas-cli build -p ios --profile connect` (ou `--profile stratton`).
3. `npx eas-cli submit -p ios --profile connect`.

O push do iPhone da Connect passa pelo servidor Connect Push (Square Cloud).
A STRATTON ainda precisa do próprio projeto no Expo (EAS) antes da primeira build de iPhone.

© OPERAÇÕES DIGITAIS - STRATTON · © OPERAÇÕES DIGITAIS - CONNECT PAY
