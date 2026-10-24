# Luna — análise das três fotos e correção visual

Base: commit `2d39d99`, branch `fix/0.1.15-restauracao`. Backup dos dois arquivos antes da edição: `backups/luna-fotos-antes-20261023-170258.zip`. APK não gerado nem instalado.

## Leitura das referências

| Foto | Referência aproveitada | Limite da imagem |
|---|---|---|
| 1, paisagem | Luna em destaque, conversa separada, identidade escura/neon, acesso claro a voz/foto/texto | Cenário, cartões, clima e textos são uma composição visual; não são controles nem dados do aplicativo. |
| 2, retrato | Boas-vindas, pergunta contextual e ações grandes | Os quatro perfis e agenda/clientes dependem dos módulos correspondentes. Não criar botões inertes ou afirmar funcionalidades ainda não entregues. |
| 3, personagem | Boné Luna, óculos, headset, rosto e identidade visual | O rosto coincide com a base do rig existente. O retrato completo inclui corpo/uniforme que o rig facial atual não anima. |

O problema encontrado era de composição: a conversa usava um rosto pequeno circular dentro do cabeçalho. A correção dá uma área própria à assistente sem substituir a interface por uma fotografia de botões.

## Alterações reais

- `app/src/main/java/space/neural/luna/avatar/LunaAvatarView.kt`: modo retrato com cantos arredondados, enquadramento completo do rig e borda que acompanha escuta/pensamento/fala; modo circular existente preservado para outros componentes. A animação facial existente continua sendo usada; nenhum corpo 3D ou animação de mãos foi declarado.
- `app/src/main/java/space/neural/luna/chat/ChatActivity.kt`: uma única Luna no painel de boas-vindas; Falar/Parar, Foto, Digitar e Ver conversa ligados aos fluxos existentes. No celular, apresentação e mensagens compartilham a rolagem. Em telas largas, apresentação lateral e conversa ao lado. Em paisagem baixa, painel compacto com os controles reais mantidos na barra inferior. Conversa nova inicia no topo para não esconder a apresentação. Histórico existente e confirmação financeira preservados.

Não reproduzidos nesta etapa: escritório fotográfico, corpo completo, mãos/caneca, seletor dos quatro perfis, agenda/clientes e navegação lateral completa da composição. Essas partes não devem ser apresentadas como concluídas. A integração Android da voz escolhida continua sendo uma dependência separada; redesenho não comprova áudio funcional.

## Validação e evidências

Capturas Robolectric em `tmp/015/luna-fotos/`, com rodapé identificando teste/renderização sem aparelho. Não são fotos do APK instalado. Foram inspecionadas telas de celular retrato, paisagem, fonte ampliada e central paisagem. A primeira rodada expôs recorte do rosto em paisagem baixa e rolagem inicial com fonte ampliada; os ajustes acima tratam esses casos.

Log da validação final: `tmp/015/luna-fotos-final-tests.log`. Resultado final e limites registrados ao terminar a execução. O APK anterior em `app/build/outputs/apk/debug/app-debug.apk` não representa estas alterações.
