#!/usr/bin/env bash
# Negative control: a wrong plugin checksum MUST stop Gradle before code is built.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
WORK="$(mktemp -d)"
cp gradle/verification-metadata.xml "$WORK/original.xml"
cleanup() {
  cp "$WORK/original.xml" gradle/verification-metadata.xml
  rm -rf "$WORK"
}
trap cleanup EXIT
python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree as E
path = Path('gradle/verification-metadata.xml')
ns = {'v': 'https://schema.gradle.org/dependency-verification'}
tree = E.parse(path)
changed = 0
for component in tree.findall('v:components/v:component', ns):
    if component.get('group') != 'org.jetbrains.kotlin' or component.get('name') != 'kotlin-gradle-plugin':
        continue
    for artifact in component.findall('v:artifact', ns):
        if not artifact.get('name', '').endswith('.jar'):
            continue
        for checksum in artifact.findall('v:sha256', ns):
            checksum.set('value', '0' * 64)
            changed += 1
assert changed, 'Cannot find a locked Kotlin plugin JAR for the negative control'
E.register_namespace('', ns['v'])
tree.write(path, encoding='utf-8', xml_declaration=True)
PY
if ./gradlew help --dependency-verification strict --no-configuration-cache > "$WORK/log" 2>&1; then
  echo 'ERROR: Gradle accepted a deliberately incorrect plugin checksum.'
  exit 1
fi
if ! grep -q 'Dependency verification failed' "$WORK/log"; then
  cat "$WORK/log"
  echo 'ERROR: Gradle failed for another reason; checksum enforcement was not demonstrated.'
  exit 1
fi
echo 'Dependency verification negative control passed: incorrect plugin checksum rejected.'
