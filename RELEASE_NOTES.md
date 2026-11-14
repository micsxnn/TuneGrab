## Segurança reforçada

- A assinatura do APK agora usa chaves guardadas nos Secrets do GitHub, fora do código. Em Android 13 ou superior, a atualização migra para a nova chave com prova de sucessão da antiga.
- O atualizador confere a origem, o SHA-256, o aplicativo, a versão e a assinatura do APK antes de abrir o instalador. APKs incompletos ou de outra origem são recusados.
- Os APKs de atualização ficam no armazenamento interno privado, não em uma pasta externa acessível a outros apps antigos.
- Links recebidos e enviados ao motor de download são validados como links do YouTube. Navegações web não podem abrir arquivos ou disparar outros aplicativos automaticamente.
- Cookies de login e a liberação por convite ficam fora dos backups e das transferências de aparelho. O login atual e os convites existentes são preservados no próprio aparelho durante a atualização.
- Reforçadas as validações de convites, cookies, letras XML e buffers do conversor MP3. XMLs excessivamente profundos/grandes são recusados antes de montar o documento.
- Atualizadas bibliotecas de HTML, JSON e arquivos compactados, além do compilador Kotlin. Os hashes das dependências ficam registrados e são exigidos pelo CI, sem aceitar mudanças silenciosas.
- A biblioteca valida os caminhos antes de mover/apagar pastas, não segue atalhos simbólicos e distingue pasta com nome em branco da raiz. O fallback da pasta de download reabre o arquivo de origem para evitar cópia truncada.
- Mantidos o fluxo de convites, os formatos, a fila de downloads e o player. Incluído o ajuste anterior do card de atualização para não trocar de release no meio do download.

### Importante sobre a assinatura

Para manter as atualizações compatíveis, Android 7 a 12 ainda usam a chave antiga, que já ficou pública. Esses aparelhos e instalações que ainda não atualizaram continuam expostos ao risco de APKs falsos assinados com essa chave. Baixe somente pelo repositório oficial.

A migração de assinatura não deve ser desfeita simplesmente republicando um APK com a chave antiga. Consulte `docs/SIGNING.md` antes de qualquer rollback.

### Validação e limites

Testes automatizados de lógica, convites, caminhos, parsers e JNI; build release; checagem criptográfica de assinatura e linhagem por faixa de Android. Não substituem teste em aparelho de atualização sobre a versão antiga, login, downloads, player e letras.

Há um aviso residual do Guava condicionado a desserialização Java não confiável, não identificada nas entradas do código do app. O detalhamento e os limites da análise estão em `docs/SECURITY_REVIEW.md` e `docs/DEPENDENCY_SECURITY.md`.
