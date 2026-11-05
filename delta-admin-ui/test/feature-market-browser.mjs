import { createServer } from 'node:http'
import assert from 'node:assert/strict'
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { chromium, devices } from 'file:///D:/codeStudy/code/cangshuo-detla-authorize/node_modules/playwright/index.mjs'
import { expect } from 'file:///D:/codeStudy/code/cangshuo-detla-authorize/node_modules/playwright/test.mjs'

const root = path.resolve('dist')
const output = path.resolve(process.env.QA_OUTPUT || 'D:/CodexData/temp/detla-entitlement')
await mkdir(output, { recursive: true })
const server = createServer(async (request, response) => {
  try {
    let file = path.resolve(root, '.' + decodeURIComponent(new URL(request.url, 'http://local').pathname))
    try { if (!(await stat(file)).isFile()) file = path.join(root, 'index.html') } catch { file = path.join(root, 'index.html') }
    response.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' }[path.extname(file)] || 'application/octet-stream' })
    response.end(await readFile(file))
  } catch { response.writeHead(404); response.end() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${server.address().port}`
const now = Date.now()
const features = [
  { id: 'advanced_income_reports', name: '收益对比分析', description: '免费扩展模块', paid: true, priceCents: 0, termDays: 3650, minVersion: '1.1.0', dependencies: ['core_platform'], entryPath: '/platform/income-analysis', capabilities: ['周期对比'], status: 'ENABLED', entitled: true, enabled: true, reason: '已启用', startsAt: new Date(now - 86400000).toISOString(), expiresAt: new Date(now + 3 * 86400000).toISOString(), managedByAuthority: true },
  { id: 'identity_verification', name: '实名认证', description: '人工审核模块', paid: true, priceCents: 2000, termDays: 365, minVersion: '1.4.0', dependencies: ['core_platform'], entryPath: '/user/identity-review', capabilities: ['实名审核'], status: 'NOT_PURCHASED', entitled: false, enabled: false, reason: '未授权', startsAt: null, expiresAt: null, managedByAuthority: true },
  { id: 'email_notifications', name: '邮件通知', description: '停用的已购模块', paid: true, priceCents: 5000, termDays: 365, minVersion: '1.4.2', dependencies: ['core_platform'], entryPath: '/system/email-notifications', capabilities: ['业务通知'], status: 'DISABLED', entitled: true, enabled: false, reason: '已授权，尚未启用', startsAt: new Date(now - 2 * 86400000).toISOString(), expiresAt: new Date(now + 5 * 86400000).toISOString(), managedByAuthority: true }
]
const results = []
try {
  for (const item of [{ name: 'Desktop', options: { viewport: { width: 1440, height: 900 } } }, { name: 'Mobile', options: devices['Pixel 7'] }]) {
    const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
    const context = await browser.newContext(item.options)
    const page = await context.newPage(), errors = []
    page.on('pageerror', error => errors.push(error.message))
    await context.addInitScript(() => {
      localStorage.setItem('delta_admin_token', 'feature-market-ui-test')
      localStorage.setItem('delta_admin_user', JSON.stringify({ id: 1, username: 'admin', nickname: '管理员', role: 'admin' }))
    })
    await page.route('**/api/**', async route => {
      const pathname = new URL(route.request().url()).pathname.replace(/^\/api/, '')
      let data = []
      if (pathname === '/system/config/site') data = { site_name: '见高山电竞', site_admin_title: '见高山电竞管理后台' }
      else if (pathname === '/cs/permissions') data = ['*']
      else if (pathname === '/admin/platform/overview') data = { customerId: '742', instanceId: 'feature-market-test', version: '1.5.10', channel: 'stable', verifierConfigured: true, licenseStatus: 'VALID', expiresAt: new Date(now + 10 * 86400000).toISOString(), allowedDomains: ['admin.example.com'] }
      else if (pathname === '/admin/platform/features') data = features
      else if (pathname === '/admin/platform/features/email_notifications/enabled' && route.request().method() === 'PUT') data = features.map(feature => feature.id === 'email_notifications' ? { ...feature, enabled: true, status: 'ENABLED', reason: '已启用' } : feature)
      else if (pathname === '/admin/platform/checkout' && route.request().method() === 'POST') data = { checkoutUrl: 'https://auth.example.com/pay/' + 'A'.repeat(43) }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data, msg: '' }) })
    })
    try {
      await page.goto(base + '/platform/features', { waitUntil: 'networkidle' })
      await expect(page.getByRole('heading', { name: '功能商城', exact: true })).toBeVisible()
      const active = page.locator('.feature-card').filter({ hasText: '收益对比分析' })
      await expect(active.getByText(/¥99\.00 · 参考 365 天 · 在线续费/)).toBeVisible()
      await expect(active.getByText(/生效 .+ · 授权至 .+ · 剩余 3 天/)).toBeVisible()
      await expect(active.getByText('授权中心同步', { exact: true }).first()).toBeVisible()
      await expect(active.locator('.el-switch')).not.toHaveClass(/is-disabled/)
      await expect(active.getByRole('button', { name: '续费', exact: true })).toBeEnabled()

      const unpurchased = page.locator('.feature-card').filter({ hasText: '实名认证' })
      await expect(unpurchased.getByText('授权期限：未授权', { exact: true })).toBeVisible()
      await expect(unpurchased.getByText(/参考 365 天 · 本次最多可购 10 天/)).toBeVisible()
      await expect(unpurchased.locator('.el-switch')).toHaveClass(/is-disabled/)
      await expect(unpurchased.getByRole('button', { name: '立即购买', exact: true })).toBeEnabled()

      const disabled = page.locator('.feature-card').filter({ hasText: '邮件通知' })
      await expect(disabled.getByText('已授权，未启用', { exact: true })).toBeVisible()
      await expect(disabled.getByText(/生效 .+ · 授权至 .+ · 剩余 5 天/)).toBeVisible()
      await expect(disabled.getByRole('button', { name: '续费', exact: true })).toBeVisible()
      await expect(disabled.locator('.el-switch')).not.toHaveClass(/is-disabled/)
      await disabled.locator('.el-switch').click()
      await expect(page.getByText('功能已启用', { exact: true })).toBeVisible()
      await expect(disabled.locator('.el-switch')).toHaveClass(/is-checked/)
      if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2)) throw new Error('horizontal overflow')
      if (errors.length) throw new Error(errors.join('\n'))
      await page.screenshot({ path: path.join(output, `feature-market-${item.name}.png`), fullPage: true, animations: 'disabled' })
      await page.route('https://auth.example.com/**', route => route.fulfill({ status: 200, contentType: 'text/html', body: 'authority checkout' }))
      const checkoutRequest = page.waitForRequest(request => request.url().endsWith('/admin/platform/checkout'))
      await Promise.all([page.waitForURL('https://auth.example.com/pay/**'), active.getByRole('button', { name: '续费', exact: true }).click()])
      assert.equal(JSON.parse((await checkoutRequest).postData()).moduleId, 'advanced_income_reports')
      results.push({ name: item.name, passed: true })
    } finally {
      await context.close()
      await browser.close()
    }
  }
  await writeFile(path.join(output, 'feature-market-browser.json'), JSON.stringify(results, null, 2))
  console.log(JSON.stringify(results))
} finally {
  await new Promise(resolve => server.close(resolve))
}
