import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('sensitive-word management is reachable from system navigation', async () => {
  const router = await read('src/router/index.js')
  const navigation = await read('src/utils/navigation.js')
  assert.match(router, /SensitiveWordList/)
  assert.match(router, /\/system\/sensitive-words|path: 'sensitive-words'|path:\s*'sensitive-words'/) 
  assert.match(navigation, /\/system\/sensitive-words/)
})

test('sensitive-word page supports filtering, editing, status and deletion through real APIs', async () => {
  const page = await read('src/views/system/SensitiveWordList.vue')
  const api = await read('src/api/system.js')
  assert.match(page, /getSensitiveWordList/)
  assert.match(page, /addSensitiveWord/)
  assert.match(page, /updateSensitiveWord/)
  assert.match(page, /deleteSensitiveWord/)
  assert.match(page, /敏感词管理/)
  assert.match(page, /低俗辱骂/)
  assert.match(page, /toggleStatus/)
  assert.match(api, /\/system\/sensitive-word\/list/)
})
