# Infraestrutura existente — auditoria de 24/10/2026

Autorização: o usuário determinou conectar ao servidor já existente e depois
autorizou criar/publicar a atualização. Este agente preparou código e testes;
root centraliza qualquer implantação/publicação. Nenhum deploy remoto,
provisionamento de usuário, serviço pago ou APK foi feito por este agente.

## Evidências locais e consultas reais

| Verificação | Resultado |
|---|---|
| APK preservado | `backups/app-0.1.14-debug.apk`, pacote `space.neural.luna`, código 114, versão 0.1.14; confirmado com aapt |
| Endereços anteriores | Os hosts de LiveKit, token e chat em `local.properties` coincidem com os recuperados da versão anterior |
| Firebase | Pacote Android correto; `.firebaserc`, `google-services.json` e projeto configurado no Worker correspondem |
| Segredos no Android | Nenhum segredo administrativo foi copiado ou adicionado |
| Worker público existente | HEAD em health/token/chat/tts retornou 405: servidor alcançável, contrato HEAD ainda não implantado |
| Manifesto público de atualização | HEAD do asset `update-manifest.json` em latest retornou 404 |
| Login Cloudflare CLI | `wrangler whoami` não autenticado; saída sensível foi capturada e não impressa |
| Credenciais GitHub locais | GH_TOKEN/GITHUB_TOKEN ausentes; gh não encontrado no PATH; helper Git existente sem autenticação conforme auditoria root |
| Workflow atual | Apenas compila debug e guarda artifact; `contents: read`, sem segredo de assinatura, manifesto ou publicação de release |
| Rotas antigas | A base Git usava o demo público OSRM e Nominatim; nenhum servidor OSRM próprio foi identificado |
| Rotas atuais | `ROUTING_BASE_URL` ausente; nenhum proxy de roteamento foi ativado neste Worker |

Não foram impressos segredos, tokens, emails, URLs com credenciais ou respostas
de emissão de token. Os testes públicos de rádio limitaram-se a cabeçalhos.

## Mudanças preparadas no Worker existente

- `/health` e `/healthz` GET/HEAD públicos e sem token, com capacidades
  booleanas e `credentialPairVerified=false` explícito.
- URL LiveKit deve ser WSS, sem credencial embutida, query ou fragmento.
- TTL de participante limitado entre 300 e 14.400 segundos; padrão anterior
  de quatro horas preservado.
- Roteamento permanece deliberadamente inativo (`routingProxyConfigured=false`).
  Health não declara mapas, SOS em produção ou voz Supertonic disponíveis.
- Auth de token usa o Firebase existente. SOS continua no Firestore e exige
  `eligibleDrivers/{uid}` controlado no servidor, sem autoinscrição do cliente.

Validação local: 21 testes backend aprovados, zero falhas; TypeScript typecheck
aprovado. Log `tmp/015/backend-contract-tests-20261024.log`.
`wrangler deploy --dry-run --outdir .wrangler/dry-run-015` produziu bundle local
de 164.908 bytes, sem upload. SHA-256 observado:
`24d3afc0cc5987f9f998cf3d2fd30733b09d38194c9aa4a024c394a8575c230a`.
O bundle mudará se novas alterações forem feitas depois desta auditoria.

## Por que LiveKit 401 ainda requer verificação real

Endereços correspondentes não comprovam que as credenciais remotas pertençam
ao projeto ativo. Sem sessão Cloudflare autenticada e sem teste de conta/app,
não foi possível validar a configuração remota ou áudio bidirecional. Não
trocar chaves por suposição nem declarar o erro resolvido por um build.

A implantação proposta deve preservar nome do Worker, bindings e segredos
existentes. Depois de login legítimo, revisar diferenças, repetir testes e
dry-run; só então root deve executar o deploy explicitamente autorizado. Não
repetir comandos de criação de namespace. Conferir nomes de segredos sem
extrair seus valores. Após publicar: health GET/HEAD, falha 401 esperada de
token sem autenticação, login real, entrada em sala e dois aparelhos com áudio.
Se o par LiveKit estiver incorreto, a correção deve usar a chave/segredo do
projeto existente mediante acesso autorizado ao painel, nunca uma chave nova
embutida no APK.

## Rota pública testada e estado de adoção

Uma consulta estática, sem localização pessoal, ao endpoint oficial de carros
FOSSGIS retornou HTTP 200/OSRM `Ok`: 1 rota, 1.833,6 m, 228 s, 35 pontos e 9
manobras. Isso valida um contrato real de amostra, não uma rota GPS do usuário.
O [frontend oficial](https://github.com/fossgis-routing-server/osrm-frontend/blob/master/src/leaflet_options.js)
identifica a base `https://routing.openstreetmap.de/routed-car`.

A [política FOSSGIS](https://routing.openstreetmap.de/about.html) exige
identificação do app, atribuição/link de correção e máximo agregado de uma
requisição por segundo, sem uso pesado. Coordenadas de rota são registradas
pelo operador. A página alemã completa vinculada retornou bloqueio Anubis e
não foi contornada. Não foi adotado o serviço automaticamente.

Para atender múltiplos aparelhos, throttle por celular ou KV eventualmente
consistente não garante esse limite agregado. Recomenda-se somente após
decisão informada: proxy autenticado no Worker existente, cache e uma porta
global serializada forte, que recuse excesso (429), em vez de filas infinitas.
Um Durable Object SQLite pode cumprir o papel no mesmo Worker; a
[documentação Cloudflare](https://developers.cloudflare.com/durable-objects/platform/limits/)
inclui SQLite no plano Free. Ainda exige conferir o plano real e aprovar novo
binding/migração; nenhum recurso ou cobrança foi criado. Sem essa garantia,
o roteamento fica desativado. Não substituir por Waze nem simular uma rota.

Nominatim público NÃO foi integrado. Sua
[política](https://operations.osmfoundation.org/policies/nominatim/)
proíbe autocomplete, impõe limite agregado/cache/identificação, exige troca de
provedor sem atualização e decisão deliberada do desenvolvedor responsável;
restringe também aplicações cujo propósito principal seja geocodificação ou
rastreamento. Busca explícita moderada não remove essas condições. Usar o
Geocoder Android existente enquanto uma escolha compatível é validada.

## Publicação do APK sem exportar a chave

A chave local de debug compatível com o APK 0.1.14 permanece local. A assinatura
de 0.1.12 publicada diverge, conforme verificação root; não prometer atualização
compatível para esses dispositivos. Um runner Ubuntu com nova chave debug
não produz a mesma identidade. Não enviar keystore ao repositório/CI.

O conector GitHub disponível lê releases, mas não oferece criação de release,
upload de asset nem dispatch de workflow. Alterar `contents: write` não cria
um canal de transporte do APK local. Não persistir centenas de MB de
fragmentos binários no Git como contorno.

Caminho preferido sem nova chave/token: sessão GitHub já autenticada no
navegador, criação de release e upload do APK local já assinado junto com hash
e manifesto. Alternativa: CLI/API com sessão/token já autorizado e escopo de
contents-write, se realmente disponível. Não extrair a credencial interna do
conector. Login real ausente é dependência operacional, não falta de
autorização do usuário para publicar.

## Servidor do app e Supertonic offline são componentes distintos

O Worker existente oferece tokens rádio, conversa e voz remota configurada.
O runtime/modelo Supertonic offline dentro de um AAR Android não é instalado
nem substituído por conectar esse servidor. Se o AAR/modelos não estiverem
presentes, não afirmar que a voz offline escolhida passou a funcionar nem
trocar silenciosamente por outra voz.
