import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL(path, import.meta.url), 'utf8')

test('admin login requires and refreshes a server-side captcha before authentication', async () => {
  const page = await read('../src/views/login/index.vue')
  const store = await read('../src/stores/user.js')
  const api = await read('../src/api/auth.js')
  assert.match(page, /captchaId/)
  assert.match(page, /captchaCode/)
  assert.match(page, /刷新图形验证码/)
  assert.match(page, /await refreshCaptcha\(\)/)
  assert.match(store, /captchaId: loginForm\.captchaId/)
  assert.match(store, /captchaCode: loginForm\.captchaCode/)
  assert.match(api, /\/cs\/auth\/captcha/)
})
