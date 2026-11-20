import test from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'

const root = process.cwd()
const baseline = new Set([
  'src/components/DemoReadOnlyPage.vue',
  'src/components/WorkbenchCard.vue',
  'src/components/WorkbenchCenter.vue',
  'src/views/chat/ChatService.vue',
  'src/views/player/Governance.vue',
  'src/views/player/PlayerList.vue'
])

function vueFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return vueFiles(path)
    return entry.isFile() && entry.name.endsWith('.vue') ? [path] : []
  })
}

test('new pages do not introduce direct request calls outside src/api', () => {
  const direct = [...vueFiles(join(root, 'src/views')), ...vueFiles(join(root, 'src/components'))]
    .filter(file => /request\.(get|post|put|delete|patch)\s*\(|request\s*\(\s*\{/.test(readFileSync(file, 'utf8')))
    .map(file => relative(root, file).replaceAll('\\', '/'))
    .sort()
  const unexpected = direct.filter(file => !baseline.has(file))
  assert.deepEqual(unexpected, [], `Move new API calls into src/api/*.js: ${unexpected.join(', ')}`)
})

test('api modules expose request paths and keep them relative', () => {
  const files = readdirSync(join(root, 'src/api')).filter(file => file.endsWith('.js'))
  for (const file of files) {
    const text = readFileSync(join(root, 'src/api', file), 'utf8')
    for (const match of text.matchAll(/url\s*:\s*(?:'([^']+)'|"([^"]+)")/g)) {
      const value = match[1] ?? match[2]
      assert.ok(value.startsWith('/'), `${file} has a non-relative API path: ${value}`)
    }
  }
})
