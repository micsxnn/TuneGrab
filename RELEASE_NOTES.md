## v0.22.31

### Cara de Android 15

- **Barras transparentes de verdade**: o app passou a desenhar de ponta a ponta — a barra de status e a de navegação ficam transparentes e o conteúdo passa por baixo delas, como nos apps feitos para o Android 15. A barra de abas desce até o pé da tela e o player ganhou o modo imersivo de verdade.
- **Funciona no Android 11 (e desde o 7)**: o mesmo visual e o mesmo comportamento valem nas versões antigas — no Android 11, por exemplo, as barras transparentes e os espaços seguros funcionam igualzinho ao 15.
- **Teclado civilizado**: quem segura o espaço agora é o app — o teclado levanta a tela em vez de cobrir o campo que você está digitando.

### Correções

- **Busca corrigida**: a busca usa primeiro o NewPipeExtractor, como no NewPipe e no YTDLnis, e usa o yt-dlp apenas como reserva. Uma resposta vazia também aciona a reserva.
- **Resultados do yt-dlp corrigidos**: o formato JSON retornado pela busca agora é interpretado corretamente, incluindo título, canal, duração, thumbnail e URL canônica.
- **Carregamento visível**: o indicador de progresso aparece durante toda a consulta e a mensagem “Nenhum resultado” só aparece depois que a busca termina.

### Continua igual

- O download direto pelo resultado continua usando o seletor de formato e o fluxo seguro já existente.
- A assinatura e a verificação dos APKs continuam iguais às versões anteriores.

Baixe somente pelo repositório oficial.
