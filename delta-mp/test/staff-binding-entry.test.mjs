import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('customer H5 keeps only a backend transfer page for staff', async () => {
  const pages = JSON.parse(await read('pages.json'))
  const staff = pages.subPackages.find(pkg => pkg.root === 'pages-cs')
  assert.deepEqual(staff.pages.map(page => page.path), ['login/index'])
  const login = await read('pages-cs/login/index.vue')
  assert.match(login, /staffLoginUrl/)
  assert.match(login, /window\.location\.replace/)
  assert.doesNotMatch(login, /csLogin\(form\)/)
  const mine = await read('pages/mine/index.vue')
  assert.doesNotMatch(mine, /@longtap/)
  assert.doesNotMatch(mine, /pages-cs\/login/)
})

test('backend owns the administrator and CS official-account binding flow', async () => {
  const router = await read('../delta-admin-ui/src/router/index.js')
  assert.match(router, /path: '\/h5\/login'/)
  assert.match(router, /buildH5Routes/)
  assert.doesNotMatch(router, /LegacyMobile/)
  const binding = await read('../delta-admin-ui/src/views/wechat/Binding.vue')
  assert.match(binding, /startOfficialWechatBinding/)
  assert.match(binding, /completeOfficialWechatBinding/)
  const api = await read('../delta-admin-ui/src/api/official-wechat.js')
  assert.match(api, /\/common\/wechat\/binding\/start/)
  const appStore = await read('store/app.js')
  const roles = await read('utils/role.js')
  assert.match(appStore, /removeCsToken\(\)/)
  assert.doesNotMatch(appStore, /switchToCs/)
  assert.doesNotMatch(roles, /pages-cs\/dashboard/)
})
