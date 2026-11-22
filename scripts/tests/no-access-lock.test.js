const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '..', '..')
const scannedRoots = [
  path.join(root, 'app', 'src', 'main'),
  path.join(root, 'access')
]

const forbiddenMarkers = [
  'AccessGate',
  'LockActivity',
  'activity_lock',
  'lock_password',
  'lock_device',
  'passwords.json'
]

function collectFiles(target) {
  if (!fs.existsSync(target)) return []
  const files = []
  for (const entry of fs.readdirSync(target, { withFileTypes: true })) {
    const fullPath = path.join(target, entry.name)
    if (entry.isDirectory()) files.push(...collectFiles(fullPath))
    else files.push(fullPath)
  }
  return files
}

const violations = []
for (const file of scannedRoots.flatMap(collectFiles)) {
  const relativePath = path.relative(root, file)
  const content = fs.readFileSync(file, 'utf8')
  for (const marker of forbiddenMarkers) {
    if (content.includes(marker)) violations.push(`${relativePath}: ${marker}`)
  }
}

if (violations.length > 0) {
  console.error(`O bloqueio por senha ainda existe:\n${violations.join('\n')}`)
  process.exit(1)
}

console.log('Bloqueio por senha removido completamente.')
