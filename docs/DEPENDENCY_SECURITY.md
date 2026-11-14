# Dependências — revisão de 14/11/2026

## Escopo e resultado

Foram diferenciados três conjuntos: dependências declaradas, componentes consultados pelo Gradle (incluem plugins/build/testes/descritores de versões não selecionadas) e o grafo **selecionado** para o APK release. Não se deve tratar um POM com aviso como prova de código vulnerável empacotado, nem limitar a consulta às dependências diretas.

O grafo selecionado do run [43591663800](https://github.com/micsxnn/TuneGrab/actions/runs/43591663800) tem **172 coordenadas Maven**. A consulta OSV de 14/11/2026 retornou **um aviso residual**, do Guava. As versões de Jackson, Commons IO e Compress anteriores estavam no APK inspecionado; foram atualizadas. A consulta não é uma garantia de ausência de vulnerabilidades.

Arquivos revisáveis:

- [Grafo release selecionado](security/runtime-dependencies.txt).
- [Resultado OSV e exceção residual](security/osv-runtime-2026-11-14.json).
- [Oito hashes comparados novamente com o Maven Central oficial](security/independent-hashes-2026-11-14.json).
- `gradle/verification-metadata.xml`: 1.232 artefatos de 765 componentes, todos com SHA-256, sem wildcard de confiança. Contém mais que o runtime.

## Atualizações aplicadas

| Componente | Antes | Agora | Alcance |
|---|---|---|---|
| jsoup | 1.18.3 | 1.23.2 | Runtime, parser de HTML |
| Jackson core/databind | 2.11.1 transitivo | 2.18.11, BOM 2.x alinhado | Runtime da biblioteca yt-dlp; sem salto para Jackson 3 |
| Commons IO | 2.5 transitivo | 2.20.0 | Runtime, cópia/arquivos |
| Commons Compress | 1.12 transitivo | 1.28.0 | Runtime, arquivos compactados |
| Commons Lang | 3.16.0 nos metadados | 3.18.0 no grafo release atualizado | Utilitários requeridos pela versão nova de Compress |
| Plugins Kotlin/Compose/serialização | 2.4.10 | 2.4.21 | Build; corrige GHSA-r937-wjx7-w2jp |
| `org.json` externo | 20240303 | 20260814 | **Somente testes JVM**; Android fornece seu próprio `org.json` |
| Desugaring JDK | 2.0.4 | 2.1.5 | Compatibilidade das APIs Java usadas pelas bibliotecas |

As versões atualizadas acima, quando consultáveis na OSV como coordenadas Maven, não retornaram avisos na consulta desta revisão. Há testes de compatibilidade para desserialização do modelo original de vídeo do yt-dlp, cópia via Commons IO e leitura/escrita TAR, além dos testes do app. Ainda são necessários testes funcionais em Android 7–12 e 13+.

## Achado residual: Guava

`com.google.guava:guava:33.0.0-android` retorna [GHSA-xxph-c9ww-hj94](https://osv.dev/vulnerability/GHSA-xxph-c9ww-hj94): coleções podem alocar memória sem limite ao receber dados de **desserialização Java nativa**. A versão 33.5.0-android também retornou esse aviso; um upgrade cego não seria uma correção.

Não foram identificados `ObjectInputStream`, `readObject` ou desserialização Java de entrada remota no código do app. O Guava continua necessário ao extrator. Isso é uma triagem limitada, **não uma prova completa de inacessibilidade** em todo código transitivo. Não introduza leitura de objetos Java serializados não confiáveis. Reavalie o aviso quando houver correção/versão oficial ou mudança nesse uso. O aviso não foi suprimido do relatório.

## Toolchain e componentes não cobertos integralmente

A consulta ampliada aos metadados também encontrou avisos em Netty, Bouncy Castle, JDOM, jose4j e HttpClient. Classes dessas famílias não foram identificadas nas definições DEX do APK inspecionado; isso não significa que a toolchain esteja isenta desses problemas. Não foram feitos upgrades arbitrários de módulos internos do AGP, o que poderia quebrar o build sem corrigir um caminho de execução conhecido do app.

Python, ffmpeg, yt-dlp embutido/atualizado remotamente, interpretador BotGuard e LAME vendorizado não receberam auditoria completa de todas as suas dependências nativas/Python. O executável yt-dlp embutido é 2025.11.12; a atualização nightly intencional foi mantida. Um smoke test offline confirmou que as novas flags são aceitas e carregam só extratores YouTube, mas não comprova extração/download real nem segurança do código remoto.

## Verificação de integridade e manutenção

O workflow final executa os testes e o APK release com **`--dependency-verification strict`** e verifica que o arquivo de hashes não mudou. `tools/test-dependency-verification.sh` é um controle negativo: exige que o Gradle rejeite um plugin com checksum deliberadamente incorreto, restaurando o arquivo no `trap`.

A geração automática de confiança usada apenas para coletar os hashes iniciais foi retirada do CI. Atualização futura:

1. Alterar versões em um ramo separado e inspecionar o grafo selecionado (`./gradlew securityRuntimeDependencies --dependency-verification strict`).
2. Em ambiente confiável, obter e comparar os novos artefatos/descritores com suas origens oficiais. Se for necessário gerar metadados em modo de manutenção, revisar o diff completo; nunca aceitar esse resultado automaticamente numa release.
3. Consultar os avisos das novas versões e manter documentadas as exceções, distinguindo runtime de build/teste.
4. Executar novamente os controles negativos, testes e release build em modo estrito; testar os fluxos relevantes em aparelho antes de declarar compatibilidade funcional comprovada.

O binário JitPack do NewPipe não foi trocado: a coordenada com o proprietário renomeado respondeu 401. O JAR original fica preso ao SHA-256 `c9e9b78627e7ae4993c447aed53ddc7ef47321e8956feaa344a572e792957167`, com seus descritores também verificados. Não forneça o token do GitHub ao JitPack para contornar esse problema.

Integridade de dependências reduz substituições silenciosas. Não substitui revisão de código, atualização de versões, proteção da conta GitHub ou backup da chave de assinatura.
