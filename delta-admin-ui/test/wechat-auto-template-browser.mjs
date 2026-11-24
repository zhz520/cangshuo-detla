// Build the UI first, then: node test/wechat-auto-template-browser.mjs <dist-dir>
// QA_PLAYWRIGHT_MODULE may be a shared installation's file: URL.
import assert from 'node:assert/strict'
import path from 'node:path'
import { access } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
const { chromium } = await import(process.env.QA_PLAYWRIGHT_MODULE || 'playwright')
const dist = path.resolve(process.argv[2] || fileURLToPath(new URL('../dist', import.meta.url)))
await access(path.join(dist, 'index.html'))

// No listening server, proxy or backend. Fulfill the built SPA and every API
// response inside a fresh browser context; never forward unknown traffic.
const origin = 'http://127.0.0.1:31781'
const entry = '/wechat/accounts'
const base = '/api/admin/wechat/accounts'
const accounts = ['a', 'b'].map(id => ({
  id, appId: 'fixture-' + id, name: 'Fixture ' + id.toUpperCase(), mode: 'OPEN_PLATFORM', enabled: true
}))
const shell = new Map([
  ['/api/cs/permissions', ['system.manage']],
  ['/api/admin/platform/features', [{ id: 'official_wechat_notifications', enabled: true, entitled: true }]],
  ['/api/system/config/site', {}], ['/api/system/config/theme', {}], ['/api/workbench/summary', {}],
  [base, accounts], ['/api/admin/wechat/notification-sender', { mode: 'AUTO', accountId: 'a', candidates: accounts }]
])
const pendingTitle = '正在后台配置模板，关闭页面不影响处理'
const attentionTitle = 'Fixture B needs attention'
const reason = 'Fixture B missing a library template'
function readiness(id, status) {
  const ready = status === 'DONE'
  return {
    templateSetup: status === 'ATTENTION'
      ? { status, message: attentionTitle, issues: [{ role: 'ADMIN', scenario: 'new_order', reason }] }
      : { status },
    templates: [{ role: 'ADMIN', scenario: 'new_order', configured: ready, enabled: ready,
      status: ready ? 'READY' : 'TEMPLATE_MISSING', templateId: ready ? id + '-template' : '' }]
  }
}

async function withFixturePage(browser, pathname, api, run) {
  const context = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1440, height: 960 } })
  const page = await context.newPage()
  page.setDefaultTimeout(12000)
  const errors = []
  page.on('pageerror', e => errors.push(e.message))
  await context.addInitScript(() => {
    localStorage.setItem('delta_admin_token', 'local-fixture')
    localStorage.setItem('delta_admin_user', JSON.stringify({ role: 'admin', username: 'fixture' }))
  })
  await context.routeWebSocket('**/*', ws => ws.close())
  await context.route('**/*', async route => {
    const req = route.request(), url = new URL(req.url()), p = url.pathname
    try {
      assert.equal(url.origin, origin, 'off-origin request blocked')
      if (p.startsWith('/api/')) {
        const data = req.method() === 'GET' && shell.has(p) ? shell.get(p) : await api(p, req)
        return await route.fulfill({ json: { code: 200, data } })
      }
      assert.equal(req.method(), 'GET', 'unexpected write: ' + p)
      const file = path.resolve(dist, '.' + (p === pathname ? '/index.html' : decodeURIComponent(p)))
      assert.ok(file.startsWith(dist + path.sep), 'invalid asset path')
      await route.fulfill({ path: file })
    } catch (e) {
      errors.push(req.method() + ' ' + p + ': ' + e.message)
      await route.abort()
    }
  })
  try {
    await run(page)
    assert.deepEqual(errors, [])
  } catch (error) {
    if (errors.length) console.error(errors)
    throw error
  } finally { await context.close() }
}

async function exercise(browser, switchDuringPoll) {
  const reads = { a: 0, b: 0 }
  let releaseOld
  const holdOld = new Promise(resolve => { releaseOld = resolve })
  async function accountApi(p, req) {
    assert.equal(req.method(), 'GET', 'unexpected write: ' + p)
    const match = /^\/api\/admin\/wechat\/accounts\/(a|b)\/(templates|readiness)$/.exec(p)
    assert.ok(match, 'unmocked API: ' + p)
    const [, id, resource] = match
    if (resource === 'templates') return [{ templateId: id + '-template', title: id + '-catalog' }]
    const seq = ++reads[id]
    if (switchDuringPoll && id === 'a' && seq === 2) await holdOld
    return readiness(id, seq === 1 ? 'PENDING' : id === 'a' ? 'DONE' : 'ATTENTION')
  }
  await withFixturePage(browser, entry, accountApi, async page => {
    const panel = page.getByRole('tabpanel', { name: '场景就绪' })
    const title = text => panel.getByText(text, { exact: true }).waitFor()
    try {
      await page.goto(origin + entry)
      await title(pendingTitle)
      if (switchDuringPoll) {
        // The first read has rendered; the timer-driven second read is held
        // until B reaches its terminal state. No refresh, retry or save click.
        if (reads.a < 2) await page.waitForRequest(origin + base + '/a/readiness')
        await page.getByRole('row').filter({ hasText: 'Fixture B' }).getByRole('button', { name: '管理' }).click()
        await page.locator('.account-summary').getByText('Fixture B', { exact: true }).waitFor()
        await title(pendingTitle)
        await title(attentionTitle)
        await panel.getByText(reason, { exact: false }).waitFor()
        const oldDelivered = page.waitForEvent('requestfinished', { predicate: req => req.url() === origin + base + '/a/readiness' })
        releaseOld()
        await oldDelivered
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
        await title(attentionTitle)
        assert.ok(await panel.getByText(reason, { exact: false }).isVisible())
        assert.ok(await panel.getByText('缺模板', { exact: true }).isVisible())
        assert.equal(await panel.getByText('a-template', { exact: true }).count(), 0)
        await page.getByRole('tab', { name: '公众号模板库' }).click()
        const catalog = page.getByRole('tabpanel', { name: '公众号模板库' })
        await catalog.getByText('b-template', { exact: true }).waitFor()
        assert.equal(await catalog.getByText('a-template', { exact: true }).count(), 0)
      } else {
        await title('模板配置完成')
        const row = panel.getByRole('row').filter({ hasText: 'a-template' })
        await row.getByText('映射就绪', { exact: true }).waitFor()
      }
      assert.deepEqual(reads, { a: 2, b: switchDuringPoll ? 2 : 0 })
    } finally { releaseOld() }
  })
  console.log('PASS:', switchDuringPoll ? 'B PENDING → ATTENTION; late A result isolated' : 'A PENDING → DONE; mapping ready', reads, 'GET only')
}

async function repairConfirmation(browser) {
  const writes=[]
  const scope='ADMIN:new_order', revision='scheme:2'
  await withFixturePage(browser,entry,async (p,req)=>{
    if(p===base+'/a/templates/provision') {
      assert.equal(req.method(),'POST'); writes.push(req.postDataJSON()); return {status:'PENDING'}
    }
    if(p===base+'/a/templates') return []
    assert.equal(p,base+'/a/readiness')
    const data=readiness('a','ATTENTION')
    data.templateSetup.revisions={[scope]:revision}
    data.templateSetup.issues[0].code='RECEIPT_NOT_IN_CATALOG'
    data.authoritySchemes=[{role:'ADMIN',scenario:'new_order',mappingMode:'SEMANTIC',revision,version:2}]
    return data
  },async page=>{
    await page.goto(origin+entry)
    const button=page.getByRole('button',{name:'补建模板',exact:true})
    await button.click()
    const dialog=page.locator('.el-message-box').filter({hasText:'补建缺失模板'})
    await dialog.getByRole('button',{name:'取消'}).click()
    assert.deepEqual(writes,[])
    await button.click()
    await Promise.all([page.waitForResponse(origin+base+'/a/templates/provision'),dialog.getByRole('button',{name:'确认补建'}).click()])
    assert.deepEqual(writes,[{action:'REBUILD_MISSING',role:'ADMIN',scenario:'new_order',revision}])
  })
  console.log('PASS: explicit per-account repair; cancel makes no request, confirm posts the selected revision once')
}

async function notificationSwitch(browser) {
  const field = { id: 42, configKey: 'wechat.official.enabled', configGroup: '公众号通知',
    configName: '公众号模板通知', valueType: 'select', configValue: 'auto' }
  const writes = []
  const configPath = '/system/config'
  async function configApi(p, req) {
    if (p === '/api/system/config/list' && req.method() === 'GET') return [field]
    assert.equal(p, '/api/system/config/batch')
    assert.equal(req.method(), 'PUT')
    const body = req.postDataJSON()
    writes.push(body)
    field.configValue = body[0].configValue
    return null
  }
  await withFixturePage(browser, configPath, configApi, async page => {
    await page.goto(origin + configPath + '?tab=login')
    const combo = page.getByRole('combobox', { name: field.configName })
    const control = page.locator('.el-form-item').filter({ has: combo })
    const save = page.getByRole('button', { name: '保存更改', exact: true })
    const auto = '自动（仅扫码授权号）'
    await control.getByText(auto, { exact: true }).waitFor()
    const expected = []
    let currentLabel = auto
    for (const [label, value] of [['停用', 'false'], ['启用', 'true'], [auto, 'auto']]) {
      await control.getByText(currentLabel, { exact: true }).click()
      await page.getByRole('option', { name: label, exact: true }).click()
      await control.getByText(label, { exact: true }).waitFor()
      assert.deepEqual(writes, expected, 'editing alone must not change stored settings')
      await save.click()
      await page.getByText('登录与微信已保存', { exact: true }).waitFor()
      expected.push([{ id: field.id, configKey: field.configKey, configValue: value }])
      assert.deepEqual(writes, expected, 'only this setting and its exact string value may be submitted')
      await page.reload()
      await control.getByText(label, { exact: true }).waitFor()
      assert.ok(await save.isDisabled(), 'reloaded value is not a draft')
      currentLabel = label
    }
  })
  console.log('PASS: notification setting 停用 → "false", 启用 → "true", 自动 → "auto"; all three persisted across reload')
}

const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined })
try {
  await exercise(browser, false)
  await exercise(browser, true)
  await repairConfirmation(browser)
  await notificationSwitch(browser)
} finally { await browser.close() }
