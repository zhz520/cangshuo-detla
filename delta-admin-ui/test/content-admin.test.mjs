import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('content pages are editable from system management with stable keys', async () => {
  const router = await read('src/router/index.js')
  const navigation = await read('src/utils/navigation.js')
  const page = await read('src/views/system/ContentSettings.vue')
  const api = await read('src/api/system.js')
  assert.match(router, /ContentSettings/)
  assert.match(navigation, /\/system\/content/)
  assert.match(page, /RichTextEditor/)
  assert.match(page, /内容键创建后不能修改/)
  assert.match(page, /用户协议/)
  assert.match(page, /隐私政策/)
  assert.match(api, /\/system\/content\/list/)
  assert.match(api, /deleteContentConfig/)
})
