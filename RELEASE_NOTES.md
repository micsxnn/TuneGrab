O aviso de versão nova ficou mais confiável.

Agora, quando você aperta "Atualizar agora" nas Configurações, ele consulta
de verdade. Antes ele fingia que tinha verificado e devolvia a resposta
guardada de antes, sem olhar nada.

Também consertei o aviso que você dispensou: ele voltava a aparecer sozinho
depois de um tempo, mesmo sem sair versão nova. Agora só avisa de novo se
sair outra versão.

O app passou a perguntar as versões novas por um caminho que não tem limite
de pedidos. Antes ele usava o endereço oficial do GitHub, que bloqueia
quem pergunta muito de um mesmo endereço de internet (no 4G é o caso de
muita gente). Era por isso que às vezes o aviso simplesmente não aparecia.
Se esse caminho falhar, o app tenta o outro, como antes.

E, se um dia o nome do arquivo do APK mudar, o app se recupera sozinho:
ele percebe que o link quebrou, pergunta o endereço certo e continua a
instalação.

Nada disso pesa no seu bolso: o repositório é público, a construção roda em
máquina do GitHub sem cobrança, e nenhuma consulta a mais foi acrescentada.
