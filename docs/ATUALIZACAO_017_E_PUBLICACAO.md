# Atualização 0.1.17 e publicação

## Estado em 24/10/2026

- O atualizador Android consulta primeiro `update-manifest.json` na release mais recente e, quando não existe manifesto, consulta a release mais recente do GitHub.
- A release pública mais recente encontrada no repositório é `v0.1.12`; as releases `v0.1.14`, `v0.1.15` e `v0.1.17` ainda não estavam publicadas.
- O código 0.1.17 está salvo na branch `codex/0.1.17-progress`.
- O workflow de publicação foi adicionado à branch, mas a execução registrada terminou antes de iniciar um runner; não houve compilação nem criação da release.

## Assinatura e atualização instalada

O Android só aceita atualização por cima quando o APK novo é assinado por uma chave compatível com a instalação existente. Foram observadas assinaturas diferentes entre APKs distribuídos em épocas distintas: a release pública 0.1.7, a release pública 0.1.12 e o APK local 0.1.17 não compartilham o mesmo certificado. O fato de um aparelho aceitar a 0.1.7 não comprova que ele aceitará um APK assinado por outra chave.

O workflow cancela a publicação caso o certificado do APK compilado não corresponda ao certificado verificado na linha pública que ele pretende atualizar. Não remover esse bloqueio para forçar uma instalação incompatível.

## Configuração do workflow

O workflow `.github/workflows/publish-0.1.17.yml` requer:

- Secret `GOOGLE_SERVICES_JSON` com a configuração Android do Firebase.
- Variáveis `LUNA_CHAT_ENDPOINT`, `LIVEKIT_URL` e `LIVEKIT_TOKEN_ENDPOINT`.
- Permissão `contents: write` para a publicação de uma GitHub Release.

O workflow confere o `versionCode`, `versionName` e certificado antes de publicar o APK e o manifesto com SHA-256. Credenciais de Firebase, Gemini, LiveKit ou administração do servidor não devem ser commitadas no repositório nem incluídas no APK.

## Validação Android

O APK local 0.1.17 foi verificado como `space.neural.luna`, `versionCode 117`, `versionName 0.1.17`, `minSdk 26`. A compilação local passou pelos testes registrados anteriormente. Isso não valida a publicação remota, a atualização in-place em todos os aparelhos, nem testes de rádio/telemetria em hardware.

## Geely e telemetria

O projeto contém perfil automotivo e um provedor de telemetria de leitura. A disponibilidade real depende das interfaces e permissões VHAL expostas pela central do veículo. Não declarar leitura de bateria, autonomia, carga, velocidade ou dados de condução como funcional até que a conexão seja exercitada no Geely e os valores observados sejam confirmados.
