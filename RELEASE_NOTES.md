## v0.22.31

### Correções

- **Busca corrigida**: a busca usa primeiro o NewPipeExtractor, como no NewPipe e no YTDLnis, e usa o yt-dlp apenas como reserva. Uma resposta vazia também aciona a reserva.
- **Resultados do yt-dlp corrigidos**: o formato JSON retornado pela busca agora é interpretado corretamente, incluindo título, canal, duração, thumbnail e URL canônica.
- **Carregamento visível**: o indicador de progresso aparece durante toda a consulta e a mensagem “Nenhum resultado” só aparece depois que a busca termina.

### Continua igual

- O download direto pelo resultado continua usando o seletor de formato e o fluxo seguro já existente.
- A assinatura e a verificação dos APKs continuam iguais às versões anteriores.

Baixe somente pelo repositório oficial.
