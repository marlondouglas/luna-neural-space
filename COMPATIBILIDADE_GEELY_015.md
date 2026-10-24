# Geely e EX Sapo — verificação 0.1.15

## Atualização local — coleta auditada em 24/10/2026

A seção de 23/10 abaixo permanece como histórico anterior às coletas. Novas evidências foram lidas de `GEELY_COLETA_FINAL/`, `MAPA_GEELY_20261023_214328/`, `MAPA_VHAL_COMPLETO.txt` e `geely-audio-policy.txt`. Nenhum VIN, serial, endereço, localização ou identificador pessoal foi copiado para este relatório. Os dumps originais podem conter dados privados e não devem acompanhar a publicação do aplicativo.

| Item | Evidência da coleta | Limite da conclusão |
|---|---|---|
| Modelo | `01-modelo.txt`: IHU629G | Identifica esta central; não define modelo de carro de todos os usuários |
| Android | `02-android.txt`: Android 9; `03-api.txt`: API 28 | Compatível com minSdk26 existente; não comprova todos os serviços da versão nova |
| ABI observada | `32-luna.txt` e `02-permissoes-luna.txt`: APK instalado com primaryCpuAbi arm64-v8a | Demonstra execução desse APK arm64; lista completa de ABIs ainda requer Build.SUPPORTED_ABIS/getprop específico |
| Tela/input | Touch X0..1919/Y0..1079. `20-dispositivos-input.txt` também informa viewport interno logicalFrame/physicalFrame1920x1080 | Coordenadas e viewport foram observados; não foi coletado `wm size`/densidade. Não fixar dp ou anunciar painel físico só pelo eixo touch |
| Áudio | Política anuncia Built-In Mic/Back Mic, speaker e saídas bus | Portas declaradas não comprovam rota escolhida ou captação livre pelo ROTA GV |
| Permissões do APK inspecionado | RECORD_AUDIO e MODIFY_AUDIO_SETTINGS concedidos | Refere-se à instalação coletada; verificar runtime em cada execução. Não confirma PTT bidirecional |
| Input | Touchscreen himax, teclado ecarx e capacidades listadas | Arquivos de eventos de volante não contêm sequência de pressionar/soltar utilizável. Não inferir mapa de teclas por catálogo |
| Car service/VHAL | Serviço Android Car e VHAL2.0, catálogo inclui velocidade e bateria | Catálogo/access de propriedade não concede permissão ao APK. Não foram efetuados get/set de telemetria ou comandos de veículo |
| Câmera | Serviços EVS/câmera presentes entre serviços | Não comprova câmera comum compatível com ACTION_IMAGE_CAPTURE; câmera permanece desabilitada pelo perfil automotivo conservador |

### Implementação segura desta rodada

Novo `automotive/AutomotiveProfile.kt`: identifica modo automotivo por FEATURE_AUTOMOTIVE, UI_MODE_TYPE_CAR, modelo observado IHU629G ou opção manual explicitamente passada pelo chamador. Paisagem/tablet, isoladamente, não ativam modo carro. Não lê serial, VIN, coordenadas ou APIs proprietárias. O perfil usa dp/fontScale de runtime, sem assumir densidade da coleta. Mapas em telas automotivas baixas usam painéis deslizantes; ferramentas recebem alvos mínimos de56dp e foco por teclado. A navegação não foi migrada, não ganhou tilt fictício e não recebe dados de direção/velocidade do VHAL.

`cameraCaptureOffered=false` no perfil automotivo; no perfil comum a indicação exige hardware declarado e Activity de captura resolvível. Esse contrato fica disponível aos chamadores sem alterar Chat/avatar nesta tarefa. `microphoneDeclared` e `microphonePermissionGranted` são informações distintas; nenhuma significa captura de áudio bem-sucedida. `telemetryAuthorized` permanece falso até existir integração autorizada e testada por propriedade.

DrivingControls agora aceita DPAD_CENTER/ENTER/NUMPAD_ENTER **somente no botão PTT focado**, Activity RESUMED, permissão de microfone atual e sessão conectada, respeitando bloqueio, transmissão e outro participante falando. Repetição de tecla não inicia outra transmissão. Soltar, perder foco, pausar ou trocar sala invalida o início pendente e encerra transmissão pelo manager existente. Não intercepta mídia/playpause, volume, tecla global de volante ou input privilegiado. Não há transmissão automática ao conectar. Nenhum evento físico do volante foi validado.

Sem CarPropertyManager oculto, reflexão de serviços privados, assinatura de plataforma, permissões privilegiadas, mudança de áudio do sistema, firmware, freio, ADAS ou comandos de veículo. A nova detecção não autoriza telemetria. Integração de leitura futura deve enumerar permissões documentadas e tratar SecurityException, unidades, timestamp/validade e usuário consentido antes de mostrar dados; nenhum desses dados foi simulado nesta versão.

### Validação desta rodada

`AutomotiveProfileTest`: quatro cenários preparados — tablet não vira carro; modelo observado com política conservadora; altura/fonte e painéis; PTT focado/foreground/conexão/repetição/soltura/cancelamento. `git diff --check` será executado antes do handoff. Não foi executado Gradle, APK, instalação, publicação ou comando no veículo por este agente; validação central permanece a cargo do agente principal. Necessários testes físicos de PTT em dois dispositivos, áudio e foco com botões reais, resolução/densidade via wm, GPS atual, cámara compatível e navegação online no host autorizado.

Consulta em 23/10/2026. Evidências públicas, sem instalação ou testes em central física.

## Evidências consultadas

- [Árvore pública CentralEXAuto](https://github.com/swimapps/CentralEXAuto/tree/main): contém APKs, manifestos JSON e README; não foi encontrado projeto Android compilável nem licença de redistribuição nessa árvore. A tentativa de ler `/main/LICENSE` retornou 404. Isso não concede autorização para reutilização.
- [README do autor](https://raw.githubusercontent.com/swimapps/CentralEXAuto/main/README.md): descreve a central IHU629G / ECarX. EX Sapo Central usa identidade de sistema e assinatura de plataforma Geely; ROTA GV mantém assinatura própria. EX Sapo Móvel sincroniza telefone/central. AAExInstall instala/atualiza; AAExCarro trata telemetria/custos no telefone. Os três últimos exigem autorização do desenvolvedor.

## Perfil ROTA GV

O projeto tem `minSdk=26` e `targetSdk=35`. Nenhuma versão Android, ABI ou certificado da central do usuário foi medido. APK disponível publicamente não comprova compatibilidade de firmware, áudio ou instalador. Não foram adicionadas permissões privilegiadas, `sharedUserId`, assinatura Geely ou alterações de firmware.

Validação física necessária: identificar Android/SDK/ABI, verificar assinatura anterior do ROTA GV, instalar pelo fluxo autorizado, testar orientação paisagem, GPS, microfone/alto-falante, mudança de saída de áudio, rede e serviço foreground. Confirmar câmera antes de disponibilizar o botão. Testar teclado e alvos de toque com o veículo estacionado.

Não há integração comprovada ou autorizada com telemetria TCP, Bluetooth proprietário ou APIs do veículo. Não há promessa de instalação via EX Sapo.

## Navegação e provedores

[Contrato OSRM](https://project-osrm.org/docs/v5.24.0/api/) usado pelo RouteEngine: coordenadas longitude/latitude, geometria GeoJSON, instruções e distância/tempo. O código preserva essa API e distingue autorização recusada (401/403), limite (429), erro de servidor e percurso inválido. Rejeita HTTP e credenciais na URL. Chaves de servidor precisam permanecer no backend.

`NavigationRepository` mantém Casa/Trabalho, favoritos e vinte destinos recentes no armazenamento privado. Sugestões durante digitação usam apenas esses destinos. A busca explícita usa Geocoder Android (disponibilidade depende do dispositivo); não usa autocomplete no Nominatim público, que é [proibido pela política oficial](https://operations.osmfoundation.org/policies/nominatim/).

[Servidor FOSSGIS](https://routing.openstreetmap.de/about.html) anuncia cobertura mundial de carro, atribuição, link para corrigir mapa, identificação do cliente e limite de uma requisição por segundo, sem uso pesado. Os termos completos no site FOSSGIS retornaram bloqueio Anubis nesta consulta. Não foi presumida autorização comercial nem garantia operacional. Um serviço público sem chave exige ainda respeito a esses termos; não equivale a infraestrutura contratada. Servidor próprio OSRM ou contrato de provedor continua sendo a opção controlável para produção.

Alternativas investigadas: [Valhalla FOSSGIS](https://valhalla.openstreetmap.de/) só expôs aplicativo JavaScript nesta consulta e continua dependendo dos termos FOSSGIS. [BRouter](https://brouter.de/) oferece aplicativo Android offline e cliente web; a página credita infraestrutura FOSSGIS e não contém autorização de uso massivo/comercial da API por terceiros. Não ativados como fallback silencioso. Liberar a tela antes do login não prova, por si só, cumprimento dos termos do serviço. A licença do motor [Valhalla](https://github.com/valhalla/valhalla) permite estudar implantação própria, mas não concede uso irrestrito de servidores públicos.

`RouteProgress.at(route, position, destination)` usa projeção no segmento da geometria, seleciona instrução por posição aproximada (sem exigir igualdade de vértices) e calcula ETA proporcional à duração da rota retornada pelo servidor. ETA é estimativa sem trânsito, não previsão ao vivo. Testes cobrem instrução que não é vértice, posição no meio de segmento longo, desvio e chegada. Falta executar na validação Gradle central.
