# Veículo, VHAL e telemetria — implementação 24/10/2026

Foi implementada camada veicular real por API pública Android Car, com diagnóstico por propriedade. Não significa que a instalação atual recebeu autorização OEM. Não foram executados comandos, leituras ou escritas no carro nesta tarefa. Não houve mudança de freio, ADAS, direção, firmware ou assinatura.

## Evidência local e autorização

Coletas auditadas: `GEELY_COLETA_FINAL/01-car-service.txt`, `02-permissoes-luna.txt`, `08-ecarx.txt`, `MAPA_GEELY_20261023_214328/43-ecarx-sdk.txt`, serviços e `MAPA_VHAL_COMPLETO.txt`. Central IHU629G/API28. O pacote inspecionado não solicita permissões CAR_ENERGY/CAR_SPEED/CAR_POWERTRAIN; não se encontrou concessão delas ao ROTA GV. Isso não é uma leitura de teste com SecurityException por propriedade: é ausência de permissão no pacote coletado. Deve ser validado na nova instalação por runtime e chamada autorizada.

O dump mostra o serviço Car e um catálogo, inclusive IDs com access3. Esse access descreve o HAL, não uma autorização ao aplicativo. Listas de permissões dos apps ECarX não se transferem ao ROTA GV. Não foi encontrado AAR/JAR cliente ECarX com contrato público/licença de integração fornecidos neste projeto; não foram reutilizados Binder, assinatura ou permissões de apps de sistema. O SDK Android local possui `android.car.jar` opcional oficial, cuja configuração Gradle/Manifest foi centralizada pelo agente principal após alinhamento.

## Código entregue

- `vehicle/VehicleModels.kt`: IDs padrão tipados, unidades, estado/idade, capacidade por área, parser de catálogo e guardas puras de escrita/SOC.
- `vehicle/VehicleProvider.kt`: contrato de conexão, capacidades, leitura, ajuste de conforto e encerramento; provider indisponível não produz valores.
- `vehicle/AndroidCarVehicleProvider.kt`: `Car.createCar(Context,ServiceConnection)`, conexão limitada a8s, `CarPropertyManager`, configurações reais, leitura tipada, checks de permissão e tratamento de SecurityException/serviço ausente/API incompatível. Sem reflexão, shell ou Binder manual.
- `vehicle/VehicleProviderFactory.kt`: aceita o perfil automotivo já identificado por feature, UI_MODE_CAR, modelo IHU629G observado ou escolha manual explícita. Assim, não rejeita a central OEM conhecida apenas porque ela omite FEATURE_AUTOMOTIVE. Essa identificação só permite **tentar a API pública**; biblioteca ausente, SecurityException ou recusa da própria API continuam produzindo diagnóstico, sem bypass ou concessão de permissão. Telefone/tablet comum sem identificação/escolha não carrega o provider.
- `vehicle/VehicleTelemetryActivity.kt`: conexão/atualização manual, pedido das permissões de leitura declaradas, dados/estado/fonte/unidade, SOC condicional, capacidades, catálogo e confirmação de conforto. Leituras expiram na tela; não são armazenadas nem sincronizadas. Ao pausar, fecha a conexão e elimina os valores da Activity.
- `assets/vehicle/geely-properties.json`:1741 propriedades únicas, **somente ID/nome/access** extraídos. Nenhum valor atual, VIN, serial, posição ou conta acompanha o arquivo. O catálogo completo é descritivo; não é um gateway de leitura/escrita por ID arbitrário.

## Leituras e diferenças OEM

IDs padrão usados: energia EV_BATTERY_LEVEL em Wh, capacidade ATUAL EV_CURRENT_BATTERY_CAPACITY em Wh, capacidade NOMINAL INFO_EV_BATTERY_CAPACITY separada, autonomia RANGE_REMAINING em metros, taxa de carga em mW, cabo conectado booleano, velocidade em m/s e CURRENT_GEAR. Fontes: [VehiclePropertyIds oficial](https://developer.android.com/reference/android/car/VehiclePropertyIds) e [CarPropertyManager](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager).

O ID padrão de autonomia é0x11600308/FLOAT. A coleta OEM anuncia RANGE_REMAINING0x11400308/INT: não foi substituído pelo nome e não foram presumidas unidades de fabricante. GEAR_SELECTION na coleta aparece como vendor0x2140800f, não o padrão0x11400400. Para estado de estacionamento usa-se CURRENT_GEAR padrão0x11400401, presente no catálogo, e `VehicleGear.GEAR_PARK` oficial, não um enum OEM adivinhado.

O catálogo coletado não contém a capacidade ATUAL padrão0x1160030d; contém capacidade NOMINAL0x11600106. Portanto SOC pode permanecer indisponível mesmo com energia legível. A fórmula só opera com energia/capacidade ATUAL válidas e frescas, nunca usando capacidade nominal como se fosse atual. `fs min/fs max` dos dumps são taxas de amostragem, não limite percentual de SOC.

Timestamp de `CarPropertyValue` é monotônico em nanossegundos. A camada não renova uma amostra antiga com o horário de consulta. Timestamp ausente/futuro ou idade acima de5s retira o valor da exibição. Estado de Park/velocidade para escrita exige ambas as amostras em até2s; propriedades ONCHANGE antigas podem falhar nessa verificação conservadora, sem inventar atualização.

## Permissões e controles de conforto

Leituras declaradas pelo principal: CAR_ENERGY, CAR_ENERGY_PORTS, CAR_SPEED, CAR_POWERTRAIN e CAR_INFO, todas no namespace `android.car.permission`. Declaração não é concessão. Usuário pode solicitar leituras pela UI; Android/OEM decide. A captura de SecurityException produz PERMISSION_DENIED e não tenta outro caminho privilegiado.

**Não solicitar CONTROL_CAR_CLIMATE como se fosse permissão comum.** A [API Car](https://developer.android.com/reference/android/car/Car) documenta controle climático como assinatura/privilegiado. O código verifica se já existe concessão legítima OEM e configuração de leitura/escrita; caso contrário o controle fica indisponível. Não há escalada, assinatura Geely ou mudança de firmware para obter isso.

Única whitelist de comandos: HVAC_TEMPERATURE_SET e HVAC_FAN_SPEED **padrão**. Temperatura aceita interseção dos limites reais da área com política conservadora16..32°C; ventilação, interseção com0..10 inteiros. Sem metadados numéricos válidos, não escreve. Não controla carga, portas, luzes externas, som AVAS ou qualquer item de condução. APIs vendor permanecem catálogo até contrato autorizado.

Antes de cada ajuste: confirmação visível para uma operação; permissão ainda concedida; config real com tipo/área/access compatíveis; duas leituras autorizadas recentes (CURRENT_GEAR=P e SPEED exatamente zero); valor permitido. Após um único set, tenta leitura de retorno até cinco vezes sem repetir o comando. Só informa sucesso se a leitura atual confirmar o valor. Sem retorno, informa que não foi possível confirmar e orienta conferir o painel original. Mudança de marcha durante a chamada não é uma transação atômica garantida; proteção do controlador original continua necessária.

Nenhum comando pode ser disparado automaticamente pela Luna, memória, dados web ou rotina em background. O booleano de confirmação é contrato interno da UI, não substitui confirmação humana em integrações futuras.

## Validação e próximos passos

Executados: auditoria de arquivos com buscas seletivas; `javap` apenas sobre o stub oficial local para confirmar APIs públicas; extração local de metadados; validação Node `CATALOG_METADATA_ONLY_VALID:1741`; `git diff --check` sem erros. Sem Gradle por este agente, para evitar concorrência com a validação central; sem APK, instalação, deploy ou teste no veículo.

Preparados nove testes: parser distingue vendor/padrão; idade/futuro; Park+zero; whitelist/limites; SOC sem capacidade nominal presumida; provider indisponível sem números/sucesso fictícios; factory sem perfil não carrega SDK; IHU629G sem feature permite tentativa; opt-in manual preserva diagnóstico de SDK ausente/recusa. O principal executará compilação e testes. Restam testes em API28 e na central: biblioteca disponível, comportamento OEM da API pública, permissões efetivas, configurações/unidades, atualidade dos valores, conforto autorizado e readback. Nenhum teste de fixture autoriza controle do veículo real.
