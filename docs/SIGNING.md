# Assinatura e migração de chave

## Situação

A chave privada antiga e sua senha estiveram no repositório público. A remoção dos arquivos não revoga cópias nem apaga o histórico. Trate a chave antiga como comprometida.

A release 0.22.28 passa a usar rotação **v3.1**, com mínimo de rotação API 33. As chaves nova e antiga já cadastradas nos Secrets são reutilizadas; não se gera uma chave nova a cada build.

| Android | Identidade usada pelo APK | Limite |
|---|---|---|
| 13+ (API 33+) | Chave nova com prova de sucessão | A migração só protege quem efetivamente atualizar. |
| 7–12 (API 24–32) | Chave antiga | Compatibilidade preservada, mas a exposição da chave antiga não está resolvida nesses sistemas. |

O suporte original do Android 9 à rotação tem limitações que a versão v3.1 melhora. Por isso esta release é mais conservadora que a proposta anterior do PR #1, que girava já na API 28. Nenhuma release publicada foi identificada com a proposta anterior durante a revisão.

Referências oficiais:
- https://developer.android.com/tools/apksigner
- https://source.android.com/docs/security/features/apksigning/v3-1
- https://developer.android.com/reference/android/provider/Settings.Secure#ANDROID_ID

## Chaves e CI

O Gradle gera um APK **sem assinatura**. `tools/sign-release.sh` assina somente em um job separado, sem executar o Gradle com os Secrets de assinatura no ambiente. PRs não recebem essas chaves e não publicam APK de release assinado.

Secrets necessários, todos com alias `tunegrab`:

| Secret | Conteúdo |
|---|---|
| `OLD_KEYSTORE_B64` | Keystore legado em base64 |
| `OLD_KEYSTORE_PASS` | Senha do keystore e da chave legados |
| `NEW_KEYSTORE_B64` | Novo keystore em base64 |
| `NEW_KEYSTORE_PASS` | Senha do novo keystore e da chave |

O diretório temporário tem permissões restritas e é removido também em falhas. Chaves/senhas não são anexadas aos artifacts. A linhagem e as impressões digitais são públicas por natureza e podem acompanhar a release.

Antes de publicar, o CI valida criptograficamente os certificados efetivamente selecionados para APIs 24–27, 28–32 e 33–37, o pacote, a versão, a ausência de `debuggable` e gera `SHA256SUMS`. A linhagem embutida precisa corresponder à linhagem pública e conter exatamente a sucessão esperada. A permissão de rollback do certificado antigo é desativada.

Impressões SHA-256 públicas, também fixadas no script para impedir troca silenciosa de Secrets:

```text
Antiga: 092f92927585924b3825d9fb5814ec445e10cbeabc6330ad841702bb45954261
Nova:   40ccf31b39211291c1172332e041aa8ec73c126a7b99df445e064fcd2d53a865
```

A antiga foi conferida no APK oficial v0.22.26, e a nova no artifact de migração já existente e no APK real do run 43591663800. Nenhuma chave privada nova foi lida ou exportada. `tools/VerifySigning.java` usa a API do `apksig`, não parsing das linhas `Signer #1`: no formato v3.1 a CLI imprime signers distintos por faixa de SDK.

Se um APK experimental do PR #1 já foi instalado no Android 9–12 usando rotação na API 28, não presuma que a política API 33 atual permita atualizar esse teste. Isso exige um APK de manutenção específico com a identidade já instalada; não use desinstalação ou rollback da chave como primeira tentativa de correção.

**Backup:** guarde a nova chave, sua senha e a linhagem em um cofre sob controle do proprietário. Os valores de Secrets não podem ser lidos de volta pela API. Esta revisão não extraiu nem substituiu a chave nova existente.

## Atualizador do app

O APK é baixado apenas de releases oficiais de `micsxnn/TuneGrab`, com HTTPS e redirecionamentos limitados ao CDN do GitHub. Antes de instalar, são conferidos SHA-256, package name, versionName, versionCode e certificados/linhagem reconhecidos pelo Android. Um APK só com a chave antiga não é aceito pelo atualizador sobre uma instalação já girada para a chave nova.

`SHA256SUMS` deve acompanhar também as releases futuras; o digest de asset da API oficial é uma alternativa. Sem checksum verificável, a atualização falha de forma segura. Checksums não substituem assinaturas nem protegem contra comprometimento da própria conta/release oficial.

## Dados, convites e rollback

A atualização que preserva os dados mantém a flag local de liberação e os favoritos. O código de aparelho depende do `ANDROID_ID`, que pode ser afetado pela assinatura: teste reativação e reinstalação ao mudar a política de assinatura. Backups/transferências não devem transportar cookies ou uma liberação vinculada a outro aparelho.

**Reverter um commit não reverte uma rotação instalada.** Para voltar o comportamento do código após o lançamento, faça uma nova release com versionCode maior, mantendo a chave nova e a linhagem. Não apague uma tag publicada para substituí-la por outro binário e não reabilite rollback para a chave comprometida.

Para uma rotação futura, preserve e estenda a linhagem existente (`apksigner rotate --in ...`). Não crie outra linhagem independente e não substitua Secrets sem um plano de migração.
