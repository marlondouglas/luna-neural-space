# Configuração do Firebase e do LiveKit (passos manuais)

Este repositório **não** guarda chaves nem segredos. O app compila sem nada disso:
sem o Firebase, os dados ficam só no celular; sem o LiveKit, a sala de voz não conecta.

## Firebase (plano gratuito Spark)

1. Entrar no [Console do Firebase](https://console.firebase.google.com) e abrir o projeto **Gemini 3**.
2. Em **Configurações do projeto → Seus apps**, abrir (ou adicionar) o app Android **`space.neural.luna`**.
3. Baixar o **`google-services.json`** e salvar em **`app/google-services.json`**.
   - Esse arquivo está no `.gitignore` e **não deve ir para o GitHub**.
   - O arquivo `app/google-services.json.example` é só um modelo e não funciona.
4. **Authentication → Método de login:** ativar **Anônimo**.
5. **Firestore Database → Criar banco de dados:** região **`southamerica-east1` (São Paulo)**, **modo de produção**.
6. **Regras de segurança** (arquivo `firestore.rules`): cada usuário só lê e grava `users/{uid}/**`.
   Publicar **só quando o Marlon decidir**, por um destes jeitos:
   - colar o conteúdo de `firestore.rules` em **Firestore → Regras → Publicar**; ou
   - com o Firebase CLI: trocar `SEU-PROJETO-FIREBASE` pelo ID do projeto em `.firebaserc` e rodar
     `firebase deploy --only firestore:rules`.
7. Gerar o APK de novo (`./gradlew assembleDebug`). Com o `google-services.json` presente,
   a sincronização liga sozinha quando houver internet.

**Limites do plano Spark (por dia):** 50 mil leituras, 20 mil gravações e 20 mil exclusões no Firestore;
1 GB de armazenamento no total. Passou do limite, a sincronização para até o dia seguinte. Não há cobrança.

## LiveKit (sala de voz / rádio)

1. Criar uma conta e um projeto no [LiveKit Cloud](https://cloud.livekit.io) (tem plano gratuito).
2. Em **Settings → Keys**, anotar a **URL do projeto** (`wss://...livekit.cloud`), a **API Key** e a **API Secret**.
   - A **API Secret nunca vai para o app nem para o GitHub**.
3. Criar um **servidor de tokens** (o app pede o token a ele). Opções:
   - o servidor de tokens de teste do LiveKit Cloud (**Sandbox → Token server**); ou
   - um servidor próprio pequeno (por exemplo, uma função na nuvem) que usa a API Key e a API Secret
     para gerar o token.
   - O app chama `GET <endpoint>?room=<sala>&identity=<usuário>` e aceita a resposta
     `{"serverUrl": "...", "participantToken": "..."}` ou `{"url": "...", "token": "..."}`.
4. No computador que gera o APK, colocar em `local.properties` (arquivo fora do Git):
   ```
   LIVEKIT_URL=wss://SEU-PROJETO.livekit.cloud
   LIVEKIT_TOKEN_ENDPOINT=https://SEU-SERVIDOR-DE-TOKENS/token
   ```
5. Gerar o APK de novo.

## Observações

- Para continuar na ligação com o app minimizado, o Android exige a permissão de microfone e,
  no Android 13 ou mais novo, a de notificações. A tela que pede essas permissões ainda vai ser feita.
- Fechar o app (tirar das tarefas recentes) ou tocar em "Sair da sala" encerra a ligação.
- O backup automático do Android está desligado (`allowBackup=false`): os dados ficam no celular
  e só vão para o Firebase pela sincronização.
