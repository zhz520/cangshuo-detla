import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const form = await readFile(new URL('../src/views/product/ProductForm.vue', import.meta.url), 'utf8')

test('product form edits service content and rich detail as separate fields', () => {
  assert.match(form, /v-model="form\.serviceContent"/)
  assert.match(form, /v-model="form\.detail"/)
  assert.doesNotMatch(form, /v-model="form\.description"/)
  assert.match(form, /description: form\.detail/)
})
