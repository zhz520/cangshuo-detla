import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('administrators and customer service can log in to the H5 staff workspace', async () => {
  const pages = JSON.parse(await read('pages.json'))
  const staff = pages.subPackages.find(pkg => pkg.root === 'pages-cs')
  assert.ok(staff.pages.some(page => page.path === 'login/index'))
  const login = await read('pages-cs/login/index.vue')
  assert.match(login, /csLogin\(form\)/)
  assert.match(login, /setCsToken\(data\.token\)/)
  assert.match(login, /appStore\.switchToCs\(\)/)
})

test('staff workspace exposes the official-account binding entry after login', async () => {
  const mine = await read('pages-cs/mine/index.vue')
  assert.match(mine, /officialWechat\.isEnabled\('cs'\)/)
  assert.match(mine, /\/pages\/wechat\/binding\?role=cs/)
  const binding = await read('pages/wechat/binding.vue')
  assert.match(binding, /\/common\/wechat\/binding\/start/)
  assert.match(binding, /请在微信内打开此页面进行绑定/)
})
