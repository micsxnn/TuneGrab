#!/usr/bin/env bash
# Assina o APK de release com ROTAÇÃO DE CHAVE (APK Signature Scheme v3).
#
# Por quê: a chave antiga vazou (estava no repositório). Rotação é a única forma
# de trocar a chave SEM obrigar ninguém a desinstalar o app:
#   - Android 9+  (API 28+): vê a chave NOVA + a prova de que veio da antiga.
#   - Android 7-8 (API 24-27): continua vendo a chave ANTIGA (não suporta rotação).
# A chave antiga perde a permissão de "rollback": um APK assinado só com ela não
# consegue mais substituir um app que já migrou para a nova.
# Detalhes e como gerar/guardar as chaves: docs/SIGNING.md
#
# Variáveis (GitHub Secrets):
#   OLD_KEYSTORE_B64 / OLD_KEYSTORE_PASS   chave original (alias "tunegrab")
#   NEW_KEYSTORE_B64 / NEW_KEYSTORE_PASS   chave nova     (alias "tunegrab")
set -euo pipefail

DIR="${1:-app/build/outputs/apk/release}"
ALIAS="tunegrab"

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
echo "build-tools: $BT"

UNSIGNED="$(ls "$DIR"/*-unsigned.apk | head -1)"
WORK="${RUNNER_TEMP:-$(mktemp -d)}/sign"
mkdir -p "$WORK"; umask 077

if [[ -z "${NEW_KEYSTORE_B64:-}" || -z "${OLD_KEYSTORE_B64:-}" ]]; then
  if [[ "${GITHUB_REF:-}" == refs/tags/v* ]]; then
    echo "::error::Secrets de assinatura ausentes: release de tag não pode sair sem assinar."
    exit 1
  fi
  echo "::warning::Secrets de assinatura ausentes (fork?). APK fica SEM assinatura."
  exit 0
fi

echo "$OLD_KEYSTORE_B64" | base64 -d > "$WORK/old.p12"
echo "$NEW_KEYSTORE_B64" | base64 -d > "$WORK/new.p12"
export OLD_KEYSTORE_PASS NEW_KEYSTORE_PASS

fp() { # SHA-256 do certificado do keystore, minúsculo e sem ":"
  keytool -list -v -keystore "$1" -storepass "$2" -alias "$ALIAS" 2>/dev/null \
    | awk '/SHA256:/ {print tolower($2)}' | tr -d ':'
}
OLD_FP="$(fp "$WORK/old.p12" "$OLD_KEYSTORE_PASS")"
NEW_FP="$(fp "$WORK/new.p12" "$NEW_KEYSTORE_PASS")"
[[ -n "$OLD_FP" && -n "$NEW_FP" && "$OLD_FP" != "$NEW_FP" ]] || { echo "::error::keystores inválidos"; exit 1; }

# Linhagem: antiga -> nova, com a antiga SEM permissão de rollback.
"$BT/apksigner" rotate --out "$WORK/lineage" \
  --old-signer --ks "$WORK/old.p12" --ks-key-alias "$ALIAS" \
    --ks-pass env:OLD_KEYSTORE_PASS --key-pass env:OLD_KEYSTORE_PASS --set-rollback false \
  --new-signer --ks "$WORK/new.p12" --ks-key-alias "$ALIAS" \
    --ks-pass env:NEW_KEYSTORE_PASS --key-pass env:NEW_KEYSTORE_PASS

# zipalign ANTES de assinar (assinar invalida o alinhamento depois).
"$BT/zipalign" -p -f 4 "$UNSIGNED" "$WORK/aligned.apk"

SIGNED="$DIR/app-release.apk"
"$BT/apksigner" sign \
  --ks "$WORK/old.p12" --ks-key-alias "$ALIAS" \
    --ks-pass env:OLD_KEYSTORE_PASS --key-pass env:OLD_KEYSTORE_PASS \
  --next-signer \
  --ks "$WORK/new.p12" --ks-key-alias "$ALIAS" \
    --ks-pass env:NEW_KEYSTORE_PASS --key-pass env:NEW_KEYSTORE_PASS \
  --lineage "$WORK/lineage" --rotation-min-sdk-version 28 \
  --out "$SIGNED" "$WORK/aligned.apk"
rm -f "$UNSIGNED" "$SIGNED.idsig"

"$BT/apksigner" verify --verbose "$SIGNED" | grep -E "Verifies|Verified using|Number of signers"

# Trava de segurança: o certificado que cada faixa de Android enxerga TEM que ser
# o esperado. Se a rotação sair errada, o build falha aqui, antes de publicar um
# APK que impediria as pessoas de atualizar.
cert() { # $1=min $2=max
  "$BT/apksigner" verify --print-certs --min-sdk-version "$1" --max-sdk-version "$2" "$SIGNED" \
    | awk '/certificate SHA-256 digest/ {print tolower($NF)}'
}
check() {
  local got; got="$(cert "$1" "$2")"
  [[ "$got" == "$3" ]] || { echo "::error::API $1-$2 enxerga '$got', esperado '$3'"; exit 1; }
  echo "OK  API $1-$2 -> ${got:0:16}..."
}
check 24 27 "$OLD_FP"
check 28 32 "$NEW_FP"
check 33 99 "$NEW_FP"

shred -u "$WORK"/*.p12 2>/dev/null || rm -f "$WORK"/*.p12
echo "APK assinado: $SIGNED"
