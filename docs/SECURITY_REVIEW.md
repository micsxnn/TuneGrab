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
| Média | Dependência JitPack referencia o nome de proprietário antigo; a coordenada do proprietário atual retornou 401. | Mantido o binário já usado, com POM e artefatos presos por SHA-256 na verificação de dependências do Gradle. Migração de coordenada fica pendente do provedor; não se envia o token do usuário ao JitPack. |
| Média | Bibliotecas indiretas antigas realmente empacotadas: Jackson 2.11.1, Commons Compress 1.12 e Commons IO 2.5; jsoup 1.18.3 direto. | Jackson 2.18.11 (BOM alinhado), Compress 1.28.0, IO 2.20.0, Lang 3.18.0 e jsoup 1.23.2. Testes exercitam o modelo do yt-dlp, cópia e arquivo TAR. Não se presume que todo aviso tivesse exploração alcançável no app. |
| Média | Kotlin Gradle Plugin 2.4.10 afetado por desserialização do cache de build. | Atualização conjunta dos plugins Kotlin/Compose/serialização para 2.4.21. `org.json` de testes atualizado para 20260814; ele não substitui o `org.json` do sistema Android. |
| Média | Chaves de pasta sem validação, symlinks e tratamento de nome só de espaços como raiz em helpers de operações destrutivas. | Validação de segmentos e caminhos canônicos, rejeição de symlinks na navegação por arquivo e uso de chave vazia — não espaço em branco — como raiz. A pasta raiz continua protegida. |
| Correção de integridade | O fallback de gravação SAF reutilizava um stream que podia já ter sido consumido. | O arquivo privado é reaberto e fechado a cada tentativa; falha parcial na pasta escolhida não publica um arquivo truncado no destino padrão. |
| Preventiva | Workflow com escrita em todo job e chaves disponíveis no mesmo job de build. | Permissão de leitura por padrão, publicação em job próprio, PRs sem assinatura, Actions fixadas por SHA e checksum da distribuição Gradle. |

## Dependências

A consulta inicial de 32 dependências diretas foi ampliada para os componentes transitivos registrados no bootstrap. Depois dos upgrades, o grafo **selecionado** `releaseRuntimeClasspath` contém **172 coordenadas Maven**. Consulta OSV em 14/11/2026: um aviso residual, do Guava, descrito abaixo. Ausência de aviso nessa consulta não significa ausência de vulnerabilidades.

O arquivo `gradle/verification-metadata.xml` contém SHA-256 de **1.232 artefatos de 765 componentes**, incluindo descritores, plugins e dependências de teste/build. Isso não é uma lista do que vai dentro do APK. Todos os artefatos listados têm SHA-256; não há wildcard `trusted-artifacts`. Oito JARs atualizados foram comparados novamente com o Maven Central oficial. O binário NewPipe original permanece com SHA-256 `c9e9b78627e7ae4993c447aed53ddc7ef47321e8956feaa344a572e792957167`.

O CI final usa `--dependency-verification strict`, sem gerar/aceitar hashes novos automaticamente. Um controle negativo troca temporariamente o checksum de um plugin por zeros e exige a rejeição pelo Gradle, restaurando o arquivo em seguida. Atualizações futuras exigem revisão explícita dos novos hashes. Detalhes, versões e exceção residual: [DEPENDENCY_SECURITY.md](DEPENDENCY_SECURITY.md).

**Guava 33.0.0-android — GHSA-xxph-c9ww-hj94:** aviso de alocação de memória durante desserialização Java nativa (`ObjectInputStream`). Não foi identificado esse uso na entrada de dados do código do app; isso não constitui prova completa de inacessibilidade em todas as bibliotecas. A versão 33.5.0-android também retornou o aviso. Não se mascarou o achado com uma atualização que continuaria afetada nem se removeu o Guava quebrando o extrator. Continua como risco condicionado a desserialização não confiável, que não deve ser introduzida.

Avisos de Netty, Bouncy Castle, JDOM, jose4j e HttpClient encontrados no conjunto de metadados **não tiveram classes das respectivas famílias identificadas no DEX do APK inspecionado**. Podem afetar ferramentas de build; isso não equivale a tê-los corrigido ou ter provado todos os caminhos de exploração da toolchain. Python, ffmpeg, LAME vendorizado e código atualizado remotamente pelo yt-dlp não receberam uma auditoria completa de terceiros.

## Testes adicionados/mantidos

- Vetores PBKDF2/HMAC e compatibilidade com o gerador de convites.
- Entradas malformadas e limites da lista de convites, sem imprimir dados reais.
- URLs legítimas, domínios falsos, credenciais, portas, IDs e intents perigosos.
- Cookies: identificação exata, controles e hash de sessão.
- Atualizações: origem/versão, SHA-256, CDN, rotação para frente e rejeição de assinatura antiga após migração.
- Letras comuns, XXE, expansão de entidades, limites de bytes, profundidade e elementos antes do DOM; respostas chunked/descomprimidas limitadas.
- Nomes de arquivo, overflow de porcentagem, segmentos de pasta e symlinks que tentam escapar ou voltar à raiz.
- JNI real no host: init inválido, frames gigantes, PCM nulo/vazio, mono, estéreo e finalização. As duas declarações locais `frameBits` do LAME foram ampliadas para o contrato de 16 entradas dos helpers; o teste nativo deixou de emitir os avisos de dimensão anteriores.

### Evidência já concluída

Run [43591663800](https://github.com/micsxnn/TuneGrab/actions/runs/43591663800), commit `2ec0808de7c506e809b961f3f6294b4be509f09b`: **118 testes JVM, zero falhas/erros/skips**, quatro regressões do gerador, selftest criptográfico, regressões JNI, APK release e assinatura passaram. Esse run ainda gerava hashes para bootstrap; não é a evidência de enforcement estrito.

O APK real desse run também foi verificado localmente com `apksig` 8.7.3: chave antiga nas APIs 24–27 e 28–32, chave nova nas APIs 33–37; linhagem embutida de dois certificados e rollback antigo desativado. O verificador usa a API estruturada: a saída textual do `apksigner` v3.1 lista dois signers por faixa e não deve ser interpretada como uma única linha `Signer #1`.

As flags de isolamento do yt-dlp foram aceitas pelo executável embutido em um smoke test **offline**, carregando 21 extratores YouTube, sem Generic. Isso não foi um download real em Android. O CI de publicação repete testes, build, enforcement estrito e validação da assinatura antes de liberar os assets.

## Limites que não devem ser escondidos

1. **Android 7–12:** a compatibilidade de atualização mantém a chave antiga nesses aparelhos. Não é possível afirmar que a exposição foi resolvida em todos os dispositivos.
2. **Instalações ainda não migradas:** continuam confiando na chave antiga. Downloads devem vir exclusivamente da origem oficial.
3. **Convites no cliente:** um APK modificado pode retirar uma trava executada inteiramente no cliente. Uso único/consumo atômico e controle real de autorização exigem um serviço de ativação. Nenhum backend, login extra ou revogação de liberações permanentes foi introduzido nesta correção.
4. **Dados identificáveis:** IDs antigos de convites estão em um JSON público e no histórico. Não foram renomeados nem apagados, para não alterar o processo de administração sem uma decisão específica.
5. **Código remoto intencional:** o app continua atualizando yt-dlp nightly e executando o desafio BotGuard de origens Google permitidas. Remover esses mecanismos mudaria o funcionamento e não fez parte deste patch.
6. **Testes em aparelho:** build/testes/verificação de assinatura não substituem testes reais de instalação sobre a versão antiga, convite, login Google, download, player e letras. Não há afirmação de que um aparelho físico tenha sido testado.
7. **Rollback:** corrigir um lançamento com rotação exige release posterior, versionCode maior e manutenção da chave/linhagem novas; um revert isolado não desfaz a identidade já instalada.

A revisão é uma análise e correção de código com testes automatizados, não uma garantia de ausência de vulnerabilidades nem um pentest completo.
