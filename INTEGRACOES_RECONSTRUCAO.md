# Integrações e dados — 23/10/2026

## Configuração local restaurada

`app/google-services.json`: arquivo fornecido em Downloads, pacote validado como `space.neural.luna`. Ignorado pelo Git. Não comprova habilitação de login nem regras publicadas.

`local.properties`: URLs anteriores de LiveKit, emissão de tokens e chat recuperadas de `tmp/pdfs/original-endpoints.json`, mantendo SDK e demais propriedades. Nenhum segredo administrativo foi adicionado. O APK contém URLs públicas de serviço, não LIVEKIT_API_SECRET ou GEMINI_API_KEY.

O Worker local foi alterado para derivar a identidade do ID token Firebase validado. **Essa correção ainda precisa de implantação no Worker existente**. Não houve deploy. O backend já possui verificação criptográfica de Firebase e política de licença com máximo padrão de três aparelhos; isso não impede acesso direto ao Firestore por um quarto aparelho. O limite de sincronização exige controle adicional no backend/regras.

## Roteamento e geocodificação

O mapa usa osmdroid/OpenStreetMap; osmdroid não calcula rotas. O cliente `RouteEngine` implementa o protocolo OSRM e lê geometria, duração, distância e manobras da resposta real.

Definir `ROUTING_BASE_URL=https://<servidor-autorizado>` em `local.properties` para um servidor OSRM compatível, após verificar cobertura, licença, disponibilidade e limite de uso. Sem isso a interface informa pendência de configuração. Nenhum serviço foi contratado.

Busca de endereço utiliza o `Geocoder` disponível no Android, fora da thread principal. O fornecedor e a disponibilidade dependem do aparelho; não é reconhecimento ou busca offline garantida. A busca Nominatim pública embutida foi retirada. A [política de Nominatim](https://operations.osmfoundation.org/policies/nominatim/) restringe uso, exige identificação, cache e limites de requisição; sua API pública não foi adotada como infraestrutura desta reconstrução.

[Contrato OSRM](https://project-osrm.org/docs/v5.24.0/api/) e [política dos servidores de demonstração](https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy). Um servidor de demonstração não foi considerado serviço de produção contratado. GraphHopper e Valhalla não foram integrados.

## Eletropostos

Não há API de operador integrada nesta entrega. `ChargingProvider` define o contrato; não foram criados adaptadores vazios com dados fictícios. `ChargingLiveStatus` transforma estados vencidos em UNKNOWN. Equipamentos, conectores e vagas não são tratados como sinônimos.

| Rede | Fonte consultada | Resultado desta execução |
|---|---|---|
| Spott | [Plataforma oficial](https://www.spott.eco/app), [API de parceiros anunciada](https://spott.eco/cpo/) | Há oferta comercial de integração; documentação e credencial autorizada não fornecidas. PENDENTE DE API OU AUTORIZAÇÃO |
| Now Charge | [FAQ oficial](https://nowcharge.com.br/faq-ouvidoria/) | Produto/rede identificados; API autorizada não confirmada. PENDENTE DE API OU AUTORIZAÇÃO |
| Clube Charger | [Mapa oficial](https://clubecharger.com/), [identificação e termos](https://clubecharger.com/terms) | Plataforma identificada; acesso ao mapa não equivale a autorização de redistribuir telemetria. PENDENTE DE API OU AUTORIZAÇÃO |
| Plug31 | Nome e endereços do pedido | Nenhuma API ou fonte primária suficiente foi confirmada. Não inferir relação comercial com outras redes |
| Energiza RECHARGE | [Cadastro público Carregados](https://carregados.com.br/estacoes/estacao-energiza-recharge-2076768) | Nome/endereço mantidos com atribuição; sem coordenadas presumidas, tarifas, potência ou ocupação apresentadas como verificadas pelo operador |
| Planalto II — BR-116 km 412 | Ponto indicado no pedido | Dados insuficientes para cadastro confiável; não incluído como estação validada |

O cadastro Energiza é uma listagem pública secundária e exige confirmação do operador para os dados técnicos/comerciais. Não é evidência de vínculo de parceria. Não foram copiados logos, fotografias comerciais, avaliações ou promoções.

## Combustíveis

A [ANP publica pesquisas semanais e preços por revendedor](https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/levantamento-de-precos-de-combustiveis-ultimas-semanas-pesquisadas). Isso não comprova preço de bomba no momento da consulta. Importador e catálogo de postos com preço/datacoleta/fonte ainda NÃO IMPLEMENTADOS. Nenhum preço foi inventado.

## Luna híbrida

Fluxo atual: `LunaConversa` serializa mensagens e confirmações → `LunaIntentParser` interpreta comandos conhecidos → repositório grava Room e auditoria → `Painel`/Flow recalculam envelopes. Navegação só aceita destinos de `LunaNavigationController`. Conversa não interpretada continua por `RemotoCerebro`/Worker Gemini, com reserva `LocalCerebro`.

Não foi instalado modelo generativo local. Não há alegação de reconhecimento de voz offline: o SpeechRecognizer depende dos serviços/modelos presentes no aparelho. O Gemini existente conversa via backend e não executa SQL; extração avançada de intenções estruturadas pelo Gemini ainda NÃO IMPLEMENTADA.

Correções financeiras registram antes/depois em auditoria local Room v4. Conflitos recebidos pela sincronização também preservam a versão substituída nessa auditoria. A política de decisão de sincronização continua sendo última escrita; interface para resolver conflitos e sincronização da própria auditoria permanecem pendentes.
