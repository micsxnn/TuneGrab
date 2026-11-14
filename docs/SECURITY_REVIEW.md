# Revisão de segurança — 0.22.28

Data: 14/11/2026. Base original da main: `00b7bc13b928f776fce9c04ced33f87b400f7a30`.

A revisão aproveita o histórico do PR #1, sem substituir os Secrets/chaves novos já existentes. Não se reescreve o histórico e não se removem releases antigas.

## Correções

| Prioridade | Constatação | Tratamento |
|---|---|---|
| Crítica | Chave privada de release e senha públicas na versão original. | Remoção do código, Secrets, assinatura isolada do build, rotação v3.1 API 33+, rollback legado desativado. **Há risco residual nas versões antigas do Android.** |
| Alta | APK de atualização guardado em armazenamento externo e validado basicamente por tamanho. | Armazenamento interno, arquivo parcial separado, SHA-256, origem/CDN HTTPS, limites, package name, versionName/versionCode e certificado/linhagem antes de instalar. |
| Alta | O backup padrão podia transportar cookies/WebView e a flag de liberação de um aparelho para outro. | Exclusões tanto no backup clássico quanto em cloud/device-transfer no Android 12+. Cookies migrados para noBackupFilesDir. Preferências/favoritos continuam elegíveis para backup. |
| Média | Validação de links divergente: caminhos como `/watch` eram canonicalizados antes de validar o domínio; metadados/fallback do yt-dlp não tinham fronteira única de validação. | Política central de URLs aplicada a entrada, extração, metadados, playlists e motor; rejeição de domínios parecidos, credenciais, portas indevidas, IDs ambíguos e esquemas locais. |
| Média | A WebView podia abrir intents externos sem gesto do usuário; Safe Browsing estava desativado globalmente. | Safe Browsing ativo, saída externa só da página principal com gesto e esquemas permitidos; bloqueio de arquivos/conteúdo na ponte PoToken. Login Google e captura de cookies mantidos. |
| Média | `device` presente mas com tipo errado era tratado como ausente na lista de convites. | Entrada malformada não vira convite sem vínculo. Limites de corpo, entradas, salt e custo PBKDF2. As quatro entradas existentes e a derivação do código de aparelho não foram alteradas. |
| Média | Cookies eram identificados por substring, o hash de sessão usava MD5 com fallback para o valor bruto, e o arquivo Netscape aceitava campos sem validação específica. | Nomes exatos, SHA-256 sem fallback para credenciais, rejeição de controles/quebras de linha e escrita atômica em diretório privado sem backup. |
| Média | Parsers de texto e XML processavam entradas de rede sem limites consistentes; flags XML de segurança podiam ser ignoradas por alguns providers. | Limites de respostas, rejeição de DOCTYPE/ENTITY antes do DOM e EntityResolver que falha fechado. |
| Média | Multiplicação de frames/canais no JNI podia estourar e havia usos de ponteiros/arrays sem checar falha de alocação. | Comparação por divisão, aritmética intermediária de 64 bits, verificação de nulos/alocações, canais e bitrate; caminho correto para PCM mono. |
| Média | Dependência JitPack referenciava o nome de proprietário antigo. | Coordenada do proprietário atual com commit completo fixado; a resolução é validada pelo build. |
| Média | jsoup 1.18.3 consta em dois avisos OSV. | Atualização para 1.23.2, consultada sem avisos retornados pela OSV nesta revisão. Os cenários de XML/custom Safelist dos avisos não foram demonstrados como exploração do app; o upgrade é preventivo. |
| Preventiva | Workflow com escrita em todo job e chaves disponíveis no mesmo job de build. | Permissão de leitura por padrão, publicação em job próprio, PRs sem assinatura, Actions fixadas por SHA e checksum da distribuição Gradle. |

## Dependências

Foram consultadas 32 coordenadas diretas de runtime/build declaradas no módulo no início da revisão. Avisos encontrados no jsoup original:
- https://osv.dev/vulnerability/GHSA-65r4-943x-97jj
- https://osv.dev/vulnerability/GHSA-pmhh-3w7g-xqp8

Isso **não** equivale a uma auditoria completa das dependências transitivas, do Python/ffmpeg, do LAME vendorizado ou do código atualizado remotamente pelo yt-dlp.

## Testes adicionados/mantidos

- Vetores PBKDF2/HMAC e compatibilidade com o gerador de convites.
- Entradas malformadas e limites da lista de convites, sem imprimir dados reais.
- URLs legítimas, domínios falsos, credenciais, portas, IDs e intents perigosos.
- Cookies: identificação exata, controles e hash de sessão.
- Atualizações: origem/versão, SHA-256, CDN, rotação para frente e rejeição de assinatura antiga após migração.
- Letras comuns, XXE, expansão de entidades, limite de DOM e respostas chunked.
- Nomes de arquivo e overflow de porcentagem.
- JNI real no host: init inválido, frames gigantes, PCM nulo/vazio, mono, estéreo e finalização.

O CI executa os testes JVM, os testes nativos e a compilação, e confere a seleção de certificados nas faixas de API antes de publicar. A assinatura final e o checksum acompanham o APK.

## Limites que não devem ser escondidos

1. **Android 7–12:** a compatibilidade de atualização mantém a chave antiga nesses aparelhos. Não é possível afirmar que a exposição foi resolvida em todos os dispositivos.
2. **Instalações ainda não migradas:** continuam confiando na chave antiga. Downloads devem vir exclusivamente da origem oficial.
3. **Convites no cliente:** um APK modificado pode retirar uma trava executada inteiramente no cliente. Uso único/consumo atômico e controle real de autorização exigem um serviço de ativação. Nenhum backend, login extra ou revogação de liberações permanentes foi introduzido nesta correção.
4. **Dados identificáveis:** IDs antigos de convites estão em um JSON público e no histórico. Não foram renomeados nem apagados, para não alterar o processo de administração sem uma decisão específica.
5. **Código remoto intencional:** o app continua atualizando yt-dlp nightly e executando o desafio BotGuard de origens Google permitidas. Remover esses mecanismos mudaria o funcionamento e não fez parte deste patch.
6. **Testes em aparelho:** build/testes/verificação de assinatura não substituem testes reais de instalação sobre a versão antiga, convite, login Google, download, player e letras. Não há afirmação de que um aparelho físico tenha sido testado.
7. **Rollback:** corrigir um lançamento com rotação exige release posterior, versionCode maior e manutenção da chave/linhagem novas; um revert isolado não desfaz a identidade já instalada.

A revisão é uma análise e correção de código com testes automatizados, não uma garantia de ausência de vulnerabilidades nem um pentest completo.
