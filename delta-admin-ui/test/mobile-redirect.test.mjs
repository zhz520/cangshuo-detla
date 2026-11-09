import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('removed /mobile pages keep a static compatibility redirect for old H5 links', async () => {
  const html = await read('index.html')
  const script = await read('public/mobile-redirect.js')
  assert.match(html, /src="\/mobile-redirect\.js"/)
  assert.match(script, /path !== '\/mobile'/)
  assert.match(script, /path\.indexOf\('\/mobile\/'\)/)
  assert.match(script, /window\.location\.replace\('\/h5'/)
  assert.match(script, /window\.location\.search/)
  assert.match(script, /window\.location\.hash/)
})
