# Supertonic 3 no Android — implementação de 24/10/2026

A voz aprovada é exclusivamente `supertonic-3-pt-sid-0`: modelo `sherpa-onnx-supertonic-3-tts-int8-2026-05-11`, idioma de síntese `pt`, speaker `0`, oito passos, velocidade 1, CPU com duas threads. Não houve troca para RHVoice/Letícia, Android TTS ou Google. A prova RHVoice anterior continua apenas comparativa.

## O que está implementado

`SupertonicVoice` implementa `LocalVoiceRuntime.Engine` usando as classes Kotlin reais do AAR oficial Sherpa-ONNX 1.13.8; a API foi inspecionada em `classes.jar` com `javap`, sem reflexão ou conversões forçadas. A dependência local foi adicionada ao Gradle. O AAR contém `arm64-v8a`, `armeabi-v7a`, `x86` e `x86_64`; preservamos todas. O app mantém minSdk 26 e a central informada é API 28/arm64. Compatibilidade declarada/compilação não comprova execução física nessa central.

Os sete arquivos do modelo ficam nos assets da atualização offline. `VoiceSettingsActivity` apresenta a identidade exata da voz, espaço necessário e licenças; apenas o toque e confirmação de instalação copiam os assets para `filesDir/luna_voice/`. Não existe download de modelo silencioso nem chamada de rede nessa tela. A instalação roda em coroutine IO, com progresso limitado a mudanças de percentual, cancelamento, limite de bytes por arquivo e SHA-256 individual. `VerifiedVoiceInstaller` aceita somente nomes previstos, usa staging no mesmo diretório privado, preserva a instalação anterior durante a cópia e restaura o backup caso a troca final falhe. Cancelamento e arquivos corrompidos não substituem a voz anterior. Não acessa finanças nem armazenamento de conta.

Só após instalação verificada a preferência é salva e o motor registrado/autorizado. Desativação interrompe a fala, remove a autorização e libera o runtime nativo fora da UI. Os modelos permanecem para reuso. O motor é carregado de forma preguiçosa na primeira síntese, em segundo plano, com mutex; cancelamento é repassado ao callback nativo. Erros de biblioteca/ABI/memória resultam em indisponibilidade real, mantendo o texto visível. Nenhum outro motor é escolhido automaticamente.

Luna e navegação compartilham o mesmo motor e fila. `LunaVoz.playback` publica `IDLE`, `PREPARING`, `PLAYING` e `UNAVAILABLE`. `PLAYING` e o lip sync começam somente depois que AudioTrack informa frames reproduzidos; não ao tocar no botão ou iniciar síntese. `firstAudioDelayMs` é o intervalo monotônico entre começar a preparar a requisição e o primeiro frame reportado. Esses dados ainda precisam ser medidos no Android real. O contrato está disponível para a UI do chat sem alterar seus arquivos nesta implementação.

## Artefatos e custo

- AAR: 50.129.134 bytes, SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.
- Arquivo oficial dos modelos: 128.774.318 bytes, SHA-256 `82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427`.
- Sete arquivos necessários descompactados: 145.295.768 bytes. A cópia privada consome esse espaço adicional, além do APK; uma reinstalação temporariamente precisa de outro staging desse tamanho para preservar o modelo anterior. A tela verifica espaço livre antes de começar.
- Pesos e AAR não devem entrar no Git: `.gitignore` contém os caminhos específicos e `git check-ignore` confirmou a exclusão. `tools/tts/android-voice-manifest.json` fixa tamanho/hash dos artefatos e de cada arquivo. `tools/tts/prepare_android_voice.ps1` reproduz o preparo com downloads verificados e extração segura; não gera APK.

## Licenças

Os avisos distribuíveis ficam em `assets/supertonic3-notices/`: licença OpenRAIL-M dos pesos/preset, Apache-2.0 do motor, MIT de ONNX Runtime e MIT do código de exemplo. A licença MIT incluída no arquivo convertido é do código de exemplo e **não substitui OpenRAIL-M dos pesos**. Fontes e hashes estão em `ATTRIBUTION.txt`. As condições OpenRAIL-M e avisos dos componentes nativos devem ser preservados na distribuição. Não há treinamento, clonagem, voz de referência enviada ou garantia de uma identidade exclusiva/humana/madura.

## Evidência e pendências

A prova real em `tools/tts/supertonic3/measurements.json` continua sendo Windows CPU: três textos variáveis sintetizados, pico de working set ~304 MiB e fatores de tempo real ~0,35–0,44. A saída observada foi 44.100 Hz. Nada disso mede RAM, latência, áudio Bluetooth ou qualidade na central API 28.

Cinco testes novos exercitam o contrato fixo da voz, instalação válida, preservação de outros dados, corrupção/tamanho excessivo, cancelamento e bloqueio de caminhos, sem carregar JNI ou os modelos grandes. A execução dos testes/build fica com o coordenador. Até validação física, não declarar voz na central como testada. Registro da Activity e navegação nas configurações também ficam com o coordenador; não publicar a tela sem esses vínculos reais.

A antiga falha de revisão automática por limite de uso ocorreu em 23/10; em 24/10 a autorização de download foi executada com sucesso. Portanto essa falha anterior não é mais o bloqueio do runtime. Os bloqueios atuais são exclusivamente validação central da compilação, integração UI e teste de áudio físico.
