# Assinatura do APK

## Resumo

- A chave de release **nunca** vai para o repositório (`*.keystore`, `*.jks`,
  `*.p12` estão no `.gitignore`).
- O Gradle gera o release **sem assinatura**. Quem assina é o CI, em
  [`tools/sign-release.sh`](../tools/sign-release.sh), usando `apksigner`.
- A assinatura usa **rotação de chave** (APK Signature Scheme v3): a chave
  antiga (que ficou pública) foi substituída por uma chave nova, e quem já
  tem o app instalado **atualiza normalmente, sem desinstalar**.

## Por que rotação

A chave antiga ficou exposta no repositório. Como o app se atualiza sozinho,
qualquer pessoa com essa chave poderia publicar um APK "de atualização" que o
Android aceitaria. Trocar a chave simplesmente faria o Android recusar a
atualização (assinatura diferente) e obrigaria todo mundo a desinstalar. A
rotação evita isso: o APK novo carrega uma **prova** (a "linhagem") de que a
chave nova é sucessora da antiga.

| Android do aparelho | Chave que ele enxerga | Observação |
|---|---|---|
| 9 ou superior (API 28+) | **nova**, com prova de sucessão | Atualiza e passa a confiar só na nova |
| 7 e 8 (API 24 a 27) | antiga | Não existe rotação nessas versões; segue atualizando |

Na linhagem, a chave antiga fica **sem a permissão de rollback**. Depois que um
aparelho (API 28+) migra, um APK assinado só com a chave vazada deixa de poder
substituir o app.

**Limite que continua valendo:** aparelhos com Android 7/8, e aparelhos que
ainda não atualizaram, continuam aceitando a chave antiga. A rotação reduz o
risco, não o elimina por completo. Quanto antes a versão com a rotação for
publicada e instalada, menor a janela.

## Secrets do GitHub

Em *Settings > Secrets and variables > Actions*:

| Secret | Conteúdo |
|---|---|
| `OLD_KEYSTORE_B64` | keystore original (alias `tunegrab`), em base64 |
| `OLD_KEYSTORE_PASS` | senha do keystore e da chave originais |
| `NEW_KEYSTORE_B64` | keystore novo (alias `tunegrab`, PKCS12), em base64 |
| `NEW_KEYSTORE_PASS` | senha do keystore e da chave novos |

Para gerar o base64: `base64 -w0 arquivo.p12`.

## O que o CI confere antes de publicar

O script `tools/sign-release.sh` verifica, depois de assinar, o certificado que
cada faixa de Android enxerga: API 24-27 deve ver a chave antiga, e API 28+ a
nova. Se algo divergir, o build falha. Isso evita publicar um APK que
impediria as pessoas de atualizar.

## Guarde o backup das chaves

**Se a chave nova for perdida, ninguém consegue mais receber atualizações
do app** (só reinstalando). Guarde o `.p12` e a senha em um gerenciador de
senhas ou cofre, **fora do Git**. Os Secrets do GitHub não podem ser lidos de
volta depois de salvos.

## Rotações futuras

Para trocar a chave de novo, encadeie a linhagem existente:

```bash
apksigner rotate --in lineage --out lineage2 \
  --old-signer --ks chave-atual.p12 --new-signer --ks chave-nova.p12
```

Depois atualize o script para assinar com a sequência completa de chaves.
