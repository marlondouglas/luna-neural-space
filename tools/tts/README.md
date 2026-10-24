# Prova local de voz: resultado real e limites

Atualização de 24/10/2026: a voz aprovada passou a ser exclusivamente Supertonic 3 Português speaker 0. A prova RHVoice abaixo é histórica/comparativa. O adapter Android real, assets offline, seleção explícita, hashes e testes estão descritos em `docs/VOZ_SUPERTONIC_ANDROID_024.md` (na raiz do projeto). Os trechos abaixo que dizem não existir adapter descrevem o estado da prova original de 23/10, não o estado atual das fontes. Validação física Android permanece separada.

Foram auditados `avatar/LunaVoz.kt`, `navigation/NavigationSpeechController.kt`, `voz/`, `voice/`, assets e ferramentas existentes. Não havia runtime nativo local, modelo ONNX nem pesos de voz; o WAV em testes é material de lip sync. O caminho anterior usava Gemini/Kore no Worker, alternativa remota e depois o TTS padrão Android. Esse caminho foi desativado por contrariar a exigência de não depender de Google nem trocar de voz silenciosamente. A navegação também deixou de inicializar o TTS padrão Android.

## Prova executada

`prove_rhvoice.py` chama diretamente a ABI C do runtime oficial RHVoice 1.18.2, com frontend oficial de português brasileiro e voz feminina existente Letícia-F123. Nenhuma instalação no Windows, serviço Google, rede durante síntese, áudio de referência, treinamento ou clonagem foi usado. A voz é licenciada de catálogo, **não uma identidade original/exclusiva da Luna**. A percepção de maturidade/naturalidade ainda precisa de avaliação humana.

Arquivos gerados: `proof/financeiro.wav`, `proof/manobra.wav`, `proof/measurements.json`. Medições desta execução Windows 11 AMD64 CPU:

| Amostra | Áudio | Síntese | Fator de tempo real |
|---|---:|---:|---:|
| Financeiro variável | 6,800 s | 0,499 s | 0,0734 |
| Manobra variável | 6,225 s | 0,248 s | 0,0398 |

Pico de working set do processo Python: 38.473.728 bytes (~36,7 MiB); carga inicial do motor: ~0,003 s. A primeira síntese inclui carregamento de voz. Não são medições de Android, Geely ou aparelho modesto. O script verificou retorno da síntese, PCM não vazio e taxa positiva; não validou audição, pronúncia nem qualidade subjetiva.

Para reproduzir, execute `fetch_proof.ps1` (aproximadamente 23 MB totais, downloads oficiais fixados e SHA-256 conferido), depois `prove_rhvoice.py` com Python Windows x64. Os arquivos extraídos ficam isolados em `proof/`, ignorados pelo Git. Não executar o instalador NVDA.

## Licenças e candidatas

- **Sherpa-ONNX motor:** [Apache-2.0 e distribuição Android oficial](https://github.com/k2-fsa/sherpa-onnx/blob/master/pom.xml); [ABIs arm64-v8a, armeabi-v7a, x86 e x86_64](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxAar/README.md). Suporte de ABI não comprova que o modelo cabe na RAM nem sua latência na central. Os modelos e frontend exigem avaliação separada.
- **Piper pt-BR:** o catálogo Rhasspy consultado contém cadu/edresson/faber/jeff; não apresentou Letícia. O [MODEL_CARD de edresson](https://huggingface.co/rhasspy/piper-voices/blob/main/pt/pt_BR/edresson/low/MODEL_CARD) atribui o dataset a CC-BY-4.0 e informa ajuste a partir de Ryan; não satisfaz uma voz feminina original. O asset Sherpa int8 pt_BR-dii é ~21 MB, mas a origem sintética e restrições NC da família exigem análise específica e autorização antes de uso comercial; não foi baixado nem apresentado como liberado.
- **Kokoro:** [pesos upstream Apache-2.0](https://huggingface.co/hexgrad/Kokoro-82M), com identidade de catálogo pf_dora. A [documentação oficial Sherpa consultada](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html) ainda adverte suporte adicionado para inglês/chinês no bundle v1.0; presença de speaker pt não demonstra frontend pt-BR funcional. O bundle v1.0 documenta ONNX ~310 MB + voices ~26 MB; o asset int8 v1.1 consultado tem 147.031.220 bytes compactados. Nenhum foi baixado sem validação de frontend. `lang` existe no contrato JNI atual, mas isso sozinho não comprova síntese portuguesa válida.
- **RHVoice motor:** [LGPL-2.1+ sem MAGE, combinação com MAGE GPL-3.0+](https://github.com/RHVoice/RHVoice/blob/master/doc/en/License.md). O binário da prova é do [release oficial 1.18.2](https://github.com/RHVoice/RHVoice/releases/tag/1.18.2), não foi incorporado ao Android. Distribuição futura precisa incluir as obrigações/licenças da composição efetiva.
- **Voz/pesos Letícia-F123:** o documento oficial acima licencia a voz CC-BY-SA-4.0, com autoria Olga Yakovleva e Fernando H. F. Botelho. Recursos fixados em [9b66982](https://github.com/RHVoice/leticia-f123-pt-br/tree/9b66982f206f0400248940afd1ac8c18518e1703). A fala gerada usa esse modelo autorizado de catálogo; não é clonagem de uma pessoa solicitada pelo usuário. A voz e seu corpus não ganham automaticamente a licença do motor; conservar atribuição e verificar obrigações para a distribuição concreta. O repositório binário não fornece um corpus gravado integral com licença separada verificável; não reivindicar direitos para treinar uma nova voz a partir dele.
- **Frontend pt-BR:** recursos oficiais [6ecd25c](https://github.com/RHVoice/Brazilian-Portuguese/tree/6ecd25c25d9a885cfe71d9d40818fd5aa6d78d1b), separados do modelo da voz. Foram usados na prova real; a futura distribuição precisa preservar avisos aplicáveis do projeto RHVoice e seus componentes.

## Integração Android preparada; voz original ainda pendente

`LocalVoiceRuntime.Engine` exige descritor de motor, pesos/voz, idioma pt-BR e licenças separadas. `register()` não autoriza uso: `authorizeEngine(id)` deve ocorrer após seleção explícita do usuário. Não há adaptador nativo instalado no APK atual, portanto o estado permanece **indisponível**, com texto e aviso reais. Não rotular como voz sintetizada nem como voz original pronta. A prova Windows não registrou um motor fictício no Android. Para completar: incorporar runtime JNI compatível/licenciado, modelo/frontend verificados, gerenciamento do download com hash/licença/consentimento, seleção na UI e testes reais. Não foram alteradas dependências Gradle nem gerado APK.

`LunaVoz` e navegação agora compartilham fila limitada: SOS > manobra > assistente, manobra nova substitui a anterior pendente, interrupção explícita e volume 0..1. `VoiceRoomManager` sinaliza TX/RX: síntese é interrompida/deferida quando o rádio ocupa o áudio; texto longo antigo não recomeça automaticamente. SOS usa o contrato de prioridade, sem alegação de integração completa do evento SOS nem de interrupção de atendimento oficial. A detecção RX depende do evento LiveKit de participante falando; a latência entre áudio recebido e esse evento não foi medida. Foco de áudio perdido interrompe reprodução. Sobreposição física, dispositivos Bluetooth, rádio em dois aparelhos e latência da central continuam sem validação real.

Testes de política adicionados em `SpeechQueuePolicyTest.kt`; execução Gradle central pendente. Nenhuma chamada Google/Android TTS é usada pela nova saída. Reconhecimento de voz é outro subsistema e não foi substituído por essa prova de síntese.
