# Sincronização entre aparelhos (v0.1.7)

Mesma conta no celular e na central do carro: tudo que é lançado num aparece no outro.

## O que sincroniza
`users/{uid}/` no Firestore:
- **Por registro** ("último a alterar vence", `updatedAt` + `deviceId`): `turnos`, `ganhos`, `gastos`, `metas`, `anotacoes`, `planejamento`, `contas_fixas`, `limites`.
- **Turno compartilhado:** `estado/turnoAtual` (último turno mexido + `versao` + aparelho).
- **Configurações compartilhadas:** `config/dashboard` (preço do kWh, limite de R$/hora, "Luna fala os alertas").
- **Relógio:** `estado/relogio_<aparelho>` (só para medir a hora do servidor).

Ficam só no aparelho: conversa com a Luna, voz/microfone (barge-in, conversa contínua, falar no chat), período do painel, adiamentos, avisos do teste, cache de clima/notícias e fotos.

## Como
- **App aberto:** `RealtimeSync` liga ouvintes (snapshot listeners) em cada coleção e nos dois documentos. A alteração local sobe na hora (300 ms de espera). No emulador, o lançamento de um aparelho aparece no outro em menos de 100 ms. Na rede real a expectativa é de 1–2 s.
- **Segundo plano / sem internet:** `SyncWorker` (WorkManager) roda depois de cada alteração, ao abrir o app, ao fazer login, ao voltar a internet e de hora em hora.
- **Envio:** cada registro sobe numa transação que só grava se a versão do aparelho for a mais nova. Se o servidor tiver uma versão mais nova, ela vem para o aparelho. Na 0.1.6 o envio sobrescrevia sempre.
- **Relógio:** os carimbos usam `Relogio.agora()`, a hora do aparelho corrigida pela do servidor (centrais de carro costumam estar com a hora errada).
- **Turno:** cada ação (iniciar, pausar, café, retomar, fechar, zerar, voltar, concluir) confere a `versao` de `estado/turnoAtual`. Se outro aparelho mexeu antes, a ação não é aplicada, o estado de lá vem para cá e aparece "O turno já foi pausado no outro aparelho." (ou equivalente). Sem internet, a ação vale no aparelho e é reconciliada depois. Os turnos têm id fixo `AAAA-MM-DD_N`, então dois aparelhos não criam dois "Turno 1/4".
- **Aparelho novo / outra conta:** "Baixando seus dados…" até a primeira descida e Planejamento travado (na 0.1.6, mexer no Planejamento de um aparelho novo apagava o do outro). Ao entrar com outra conta, os dados locais da anterior são apagados do aparelho (a nuvem dela fica intacta). "Sair da conta" tenta subir o pendente e avisa se não conseguir.

## Regras
`firestore.rules` **não mudou**: `users/{uid}/**` só do dono já cobre os caminhos novos. Teste no emulador (não publica nada):
```
cd tools/firestore-rules-test && npm i
FIRESTORE_EMULATOR_HOST=127.0.0.1:8090 node sync.test.mjs
```
Teste Kotlin de dois aparelhos com o `FirestoreRemote` de verdade no emulador (projeto `demo-rotagv-kotlin`, regras abertas):
`./gradlew testDebugUnitTest -Dluna.emulador=127.0.0.1:8090 --tests '*FirestoreEmuladorTest'`
