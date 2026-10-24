# Atualização 0.1.17 e publicação

## Estado em 24/10/2026

- O atualizador Android consulta primeiro `update-manifest.json` na release mais recente e, quando não existe manifesto, consulta a release mais recente do GitHub.
- A release pública mais recente encontrada no repositório é `v0.1.12`; as releases `v0.1.14`, `v0.1.15` e `v0.1.17` ainda não estavam publicadas.
- O código 0.1.17 está salvo na branch `codex/0.1.17-progress`.
- O workflow de publicação foi adicionado à branch, mas a execução registrada terminou antes de iniciar um runner; não houve compilação nem criação da release.
- O usuário confirmou que a 0.1.7 pública instalou por cima da versão que estava no aparelho. A assinatura da 0.1.7 tem SHA-256 `55a0decf63386c0469bdd09f012a1be5d73c90cba5b444bcf77f64c8d8185be4`.

## Assinatura e atualização instalada

O Android só aceita atualização por cima quando o APK novo é assinado por uma chave compatível com a instalação existente. O APK local 0.1.17 usa SHA-256 `3a714a3f1f6bd4454f768390e33d1f556f035fd6ea3c99c274c0b2d5daa00c4f`; a release pública 0.1.12 usa `69cd0c7ef014ac419781c96304605b581e4402693383cd21c6c0e0199dd79ec8`. A release pública 0.1.7 usa um terceiro certificado. Portanto, o APK local não atualiza por cima da instalação confirmada pelo usuário. A verificação já implementada em `AtualizacaoActivity.validarApk` bloqueia essa troca antes de abrir o instalador.

O workflow cancela a publicação caso o certificado do APK compilado não corresponda ao certificado configurado. Não remover esse bloqueio para forçar uma instalação incompatível. Para a distribuição atual pelo `latest` público, o certificado precisa corresponder à 0.1.12; para o aparelho confirmado na conversa, precisa corresponder à 0.1.7. Atender todas as instalações exige preservar cada linha de assinatura e fazer o atualizador escolher o APK compatível.

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

