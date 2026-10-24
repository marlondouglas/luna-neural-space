# Rota dos Parceiros — retomada em 22/10/2026

## Resultado confirmado

- Projeto: `C:\Users\MARLO\luna-neural-space`.
- Branch: `fix/0.1.12-correcoes`.
- Build `:app:assembleDebug`: **BUILD SUCCESSFUL in 6m 7s**.
- APK: `C:\Users\MARLO\luna-neural-space\app\build\outputs\apk\debug\app-debug.apk`.
- Tamanho observado: 69.416.809 bytes.
- Versão configurada: 0.1.13, versionCode 113, pacote `space.neural.luna`.
- Log: `C:\Users\MARLO\AppData\Local\Temp\rota-build-debug.log`.
- Não houve edição do código do aplicativo, publicação no GitHub ou alteração de credenciais.

## Ambiente preparado

- JDK 17 instalado em `C:\Users\MARLO\AppData\Local\Programs\Java\jdk-17.0.20.1+1`.
- JAVA_HOME do usuário aponta para esse JDK.
- ANDROID_HOME do usuário aponta para `C:/Users/MARLO/AppData/Local/Android/Sdk`.
- PATH do usuário recebeu os caminhos de Java, platform-tools e cmdline-tools/latest/bin.
- SDK tem Platform 35, Build Tools 36.0.0, Platform Tools e Command-line Tools.
- `local.properties` já existia com sdk.dir correto e foi preservado.
- Licença padrão do SDK já estava registrada; nenhuma nova aceitação foi realizada.
- `gradle/gradle-daemon-jvm.properties` já estava presente como arquivo não rastreado antes destas instalações. Seleciona Java 25. Foi preservado.
- O build usou o daemon Java 25 do Android Studio; o launcher usou JAVA_HOME/JDK 17.

## Próximo trabalho solicitado

1. Mostrar uma captura REAL da tela atual do rádio. Ainda não foi capturada.
2. Para isso, instalar o APK no emulador existente `Medium_Phone_API_37.0` e navegar pelo botão Rádio da tela inicial, ou usar o celular após autorização de depuração USB pelo usuário.
3. O celular `R52X50G0T3E` apareceu como unauthorized na última verificação.
4. O emulador iniciado para a captura foi encerrado para liberar RAM. O Windows tem aproximadamente 8 GB de RAM; os 92 GB citados pelo usuário são armazenamento livre.
5. Preferir compilar e executar emulador em momentos separados. Não fechar programas pessoais sem autorização.

## Referência visual do rádio

Imagem fornecida pelo usuário: `C:\Users\MARLO\OneDrive\Imagens\Imagem do ChatGPT 22 de out. de 2026, 18_12_17.jpg`.

O usuário quer o rádio como nessa imagem: layout horizontal, mapa ao fundo, topo com salas, painel esquerdo de busca/rotas/recentes, painel direito de participantes e chat, botão circular roxo grande de segurar para falar, status online/ocupado/offline, botão Reportar, navegação inferior. Painéis escuros com contornos luminosos roxos, ciano, verde e magenta; marcadores de motoristas com avatares e carros, eletropostos com ocupação e alertas no mapa.

A tela existente está em `app/src/main/java/space/neural/luna/pages/MapRadioActivity.kt`. Tem estrutura básica em três colunas, mas ainda não corresponde visual ou funcionalmente à referência. Criar sala é placeholder; Reportar não envia alerta real; lista de participantes não é alimentada; seleção de sala só muda texto/estado local. O redesenho ainda não foi implementado.

## Restrições persistentes

- Preservar funcionalidades e arquivos existentes.
- Não excluir arquivos ou alterar credenciais, assinaturas ou serviços de produção.
- Não publicar no GitHub sem autorização.
- Instalações do ambiente, variáveis, configuração local e correções pontuais de compilação já autorizadas.
- Apresentar novas licenças do SDK para aceitação se forem necessárias.
- Solicitar autorização antes de alterações importantes.

## Avisos do build

O build concluiu com avisos, incluindo condição sempre verdadeira em LunaMotor.kt, bibliotecas nativas empacotadas sem remoção de símbolos e recursos Gradle obsoletos para futura migração ao Gradle 10. Não impediram gerar o APK. Ainda não houve teste do app em execução.
