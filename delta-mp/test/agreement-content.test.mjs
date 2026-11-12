import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('agreement pages consume the content_config configValue returned by the backend', async () => {
  const page = await readFile(new URL('../pages/agreement/index.vue', import.meta.url), 'utf8')
  assert.match(page, /res\.data\?\.configValue/)
  assert.doesNotMatch(page, /res\.data\?\.content(?!Value)/)
})
