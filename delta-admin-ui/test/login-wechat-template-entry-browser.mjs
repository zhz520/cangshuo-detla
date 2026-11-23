// Built SPA only: node test/login-wechat-template-entry-browser.mjs <local-dist-dir>
// Same QA_PLAYWRIGHT_MODULE (module or file: URL) and PLAYWRIGHT_CHROMIUM_EXECUTABLE as the other browser fixtures.
import assert from 'node:assert/strict'
import { access } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'

const dist = path.resolve(process.argv[2] || fileURLToPath(new URL('../dist', import.meta.url)))
const origin = 'http://127.0.0.1:31782' // Interception only: no server, proxy or network fallback.
const settings = '/system/config', accounts = '/wechat/accounts', feature = 'official_wechat_notifications'
const ordinary = { id: 1, configKey: 'auth.password.enabled', configValue: 'true',
  configName: '启用密码辅助登录', configGroup: '账号与登录', valueType: 'boolean' }
function settingsRows() {
  // Intentionally include legacy values in the GET response. Removing them from
  // the mock would make an unchanged template editor appear to pass.
  return [{ ...ordinary }, { id: 2, configKey: 'wechat.official.enabled', configValue: 'auto',
    configName: '公众号模板通知', configGroup: '公众号通知', valueType: 'select' },
  ...['user', 'player', 'admin', 'cs'].map((role, i) => ({
    id: i + 3, configKey: 'wechat.official.templates.' + role, configValue: '{"new_order":{"templateId":"fixture-only"}}',
    configName: '旧模板夹具-' + role, configGroup: '公众号通知', valueType: 'json'
  })), { id: 7, configKey: 'wechat.official.template.new_order', configValue: 'old-only',
    configName: '旧模板夹具-new_order', configGroup: '公众号通知', valueType: 'text' },
  { id: 8, configKey: 'auth.wechat.enabled', configValue: 'false',
    configName: '启用网页微信登录', configGroup: '账号与登录', valueType: 'boolean' }]
}

let browser
before(async () => {
  await access(path.join(dist, 'index.html'))
  const { chromium } = await import(process.env.QA_PLAYWRIGHT_MODULE || 'playwright')
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined })
})
after(async () => { await browser?.close() })

async function fixture(t, { mobile = false, manage = true, entitled = true, enabled = true, allowSave = false } = {}) {
  const ctx = await browser.newContext({ serviceWorkers: 'block', viewport: mobile
    ? { width: 390, height: 844 } : { width: 1440, height: 960 } })
  const errors = [], writes = [], rows = settingsRows()
  t.after(async () => { await ctx.close(); assert.deepEqual(errors, [], 'unexpected request or browser error') })
  await ctx.addInitScript(() => {
    localStorage.setItem('delta_admin_token', 'fixture-only')
    localStorage.setItem('delta_admin_user', JSON.stringify({ role: 'admin', username: 'fixture-admin' }))
  })
  await ctx.routeWebSocket('**/*', ws => ws.close())
  const configPage = (mobile ? '/h5' : '') + settings, accountPage = (mobile ? '/h5' : '') + accounts
  const gets = new Map([
    ['/api/cs/permissions', manage ? ['system.manage'] : []],
    ['/api/admin/platform/features', [{ id: feature, enabled, entitled }]],
    ['/api/system/config/site', {}], ['/api/system/config/theme', {}], ['/api/workbench/summary', {}]
  ])
  if (manage) gets.set('/api/system/config/list', rows)
  if (manage && entitled && enabled) {
    gets.set('/api/admin/wechat/accounts', [])
    gets.set('/api/admin/wechat/notification-sender', { mode: 'AUTO', candidates: [] })
  }
  await ctx.route('**/*', async route => {
    const req = route.request(), { origin: host, pathname: p } = new URL(req.url())
    try {
      assert.equal(host, origin, 'off-origin request blocked')
      if (p.startsWith('/api/')) {
        let data
        if (req.method() === 'GET' && gets.has(p)) data = gets.get(p)
        else {
          assert.ok(allowSave && p === '/api/system/config/batch' && req.method() === 'PUT', 'unexpected API')
          const body = req.postDataJSON()
          assert.deepEqual(body, [{ id: ordinary.id, configKey: ordinary.configKey, configValue: 'false' }])
          writes.push(body)
          rows[0].configValue = body[0].configValue
          data = null
        }
        return await route.fulfill({ json: { code: 200, data } })
      }
      assert.equal(req.method(), 'GET')
      const asset = req.isNavigationRequest() && [configPage, accountPage].includes(p) ? '/index.html' : decodeURIComponent(p)
      const filename = path.resolve(dist, '.' + asset)
      assert.ok(filename.startsWith(dist + path.sep))
      await route.fulfill({ path: filename })
    } catch (e) {
      errors.push(req.method() + ' ' + p + ': ' + e.message)
      await route.abort()
    }
  })
  const page = await ctx.newPage()
  page.setDefaultTimeout(12000)
  page.on('pageerror', e => errors.push(e.message))
  await Promise.all([
    ...(manage ? [page.waitForResponse(r => r.url() === origin + '/api/admin/platform/features')
      .then(r => r.finished())] : []),
    page.goto(origin + configPage + '?tab=login')
  ])
  // Do not match the global sidebar or an unrelated navigation tab.
  const workspace = page.locator('.settings-workspace')
  const toggle = workspace.getByRole('switch', { name: '允许辅助密码登录' })
  // Element Plus exposes the checked state on a zero-size native input; the wrapper is the visible target.
  const switchControl = workspace.locator('.el-form-item').filter({ has: page.getByRole('switch', { name: '允许辅助密码登录' }) }).locator('.el-switch')
  const entry = workspace.getByRole('button', { name: '管理公众号模板', exact: true })
  const cardHead = workspace.locator('#config-panel-wechat .settings-panel-head')
  if (manage) {
    await switchControl.waitFor()
    await cardHead.waitFor() // Unlicensed admins still see normal WeChat login fields.
    assert.equal(await workspace.locator('.template-editor').count(), 0, 'old editor is still mounted')
    assert.equal(await workspace.getByText(/^旧模板夹具-/).count(), 0, 'legacy settings exposed as ordinary fields')
    assert.equal(await workspace.getByRole('button', { name: '使用推荐字段' }).count(), 0)
  }
  return { page, entry, cardHead, toggle, switchControl, workspace, writes, accountPage }
}

for (const mobile of [false, true]) {
  test((mobile ? 'H5' : 'PC') + ': authorized admin opens existing Accounts page from login settings', async t => {
    const { page, accountPage, cardHead } = await fixture(t, { mobile })
    await cardHead.getByRole('button', { name: '管理公众号模板', exact: true }).click()
    await page.waitForURL(origin + accountPage)
    await page.locator('.wechat-accounts').getByText('尚未绑定公众号', { exact: true }).waitFor()
  })
}

test('saving ordinary login settings sends exactly one ordinary field, then reads it back', async t => {
  const { page, toggle, switchControl, workspace, writes } = await fixture(t, { allowSave: true })
  const save = workspace.getByRole('button', { name: '保存更改', exact: true })
  assert.equal(await toggle.getAttribute('aria-checked'), 'true')
  await switchControl.click()
  assert.equal(writes.length, 0)
  await save.click()
  await page.getByText('登录与微信已保存', { exact: true }).waitFor()
  await page.reload()
  await switchControl.waitFor()
  assert.equal(await toggle.getAttribute('aria-checked'), 'false')
  assert.ok(await save.isDisabled())
  assert.equal(writes.length, 1)
})

for (const [label, flags] of [['not entitled', { entitled: false }], ['feature disabled', { enabled: false }]]) {
  test(label + ': ordinary settings remain accessible without a WeChat entry', async t => {
    const { page, entry, accountPage } = await fixture(t, flags)
    assert.equal(await entry.filter({ visible: true }).count(), 0)
    await page.goto(origin + accountPage)
    await page.waitForURL(origin + '/403')
  })
}

test('missing system.manage: neither settings nor the direct WeChat route is accessible', async t => {
  const { page, accountPage, entry } = await fixture(t, { manage: false })
  await page.waitForURL(origin + '/403')
  assert.equal(await entry.count(), 0)
  await page.goto(origin + accountPage)
  await page.waitForURL(origin + '/403')
})
