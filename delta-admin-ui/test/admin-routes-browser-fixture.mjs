import assert from 'node:assert/strict'
import path from 'node:path'
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { fixtureResponse, fixtureToken } from '../../deploy/qa/theme-route-fixtures.mjs'

export let origin
// Transfer compiled assets over loopback, not as multi-MB base64 CDP messages
// for every module in every fresh context. This server has no API or upstream.
export async function serveDist(dist) {
  const buffers = new Map(), types = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html',
    '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.ico': 'image/x-icon' }
  const server = createServer(async (req, res) => {
    try {
      assert.equal(req.method, 'GET')
      const p = decodeURIComponent(new URL(req.url, origin).pathname)
      assert.ok(!p.startsWith('/api/'))
      const file = path.resolve(dist, '.' + (req.headers['sec-fetch-dest'] === 'document' ? '/index.html' : p))
      assert.ok(file.startsWith(dist + path.sep))
      if (!buffers.has(file)) buffers.set(file, await readFile(file))
      res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' })
      res.end(buffers.get(file))
    } catch { res.writeHead(404); res.end() }
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  origin = 'http://127.0.0.1:' + server.address().port
  return () => new Promise(resolve => { server.close(resolve); server.closeAllConnections() })
}
export const ok = data => ({ status: 200, body: { code: 200, data } })
// Additional admin reads not covered by the shared synthetic QA data.
const arrays = new Set([
  '/admin/wechat/accounts', '/system/config/list', '/admin/recommend-category/list',
  '/admin/coupons', '/admin/coupons/records', '/admin/cs-routing/groups', '/admin/cs-routing/rules',
  '/admin/cs-routing/staff', '/admin/flash-sales', '/admin/privacy/requests', '/admin/settlements',
  '/admin/wechat/notifications', '/cs/chat/sessions', '/cs/governance/invitations',
  '/cs/governance/revisions', '/cs/governance/shifts', '/system/config/recharge-packages', '/admin/email-notifications/records'
])
const objects = new Set([
  '/admin/cs-routing/ratings', '/admin/email-notifications/settings', '/admin/home/dashboard',
  '/admin/identity-service/limits', '/admin/identity-service/settings', '/admin/invites', '/admin/membership',
  '/admin/player-layout', '/admin/reward-grab/settings', '/admin/sms/settings', '/admin/statistics/income-daily',
  '/admin/statistics/player', '/admin/statistics/user', '/cs/analytics', '/cs/dashboard', '/cs/shift/status', '/system/roles'
])
const pages = new Set(['/admin/chat/message/list', '/admin/chat/session/list', '/admin/player/deposits',
  '/admin/rewards', '/cs/deposit-refunds/list'])
function read(pathname) {
  if (arrays.has(pathname)) return ok([])
  if (objects.has(pathname)) return ok({})
  if (pages.has(pathname)) return ok({ records: [], total: 0, current: 1, size: 10 })
  if (pathname === '/admin/wechat/notification-sender') return ok({ mode: 'AUTO', candidates: [] })
  if (pathname === '/admin/user/900001') return ok({ id: 900001, nickname: '合成测试用户', status: 'ACTIVE' })
  if (pathname === '/admin/identity/applications/900001') return ok(null)
  // ProductForm accepts a comma-delimited image list; a data URI contains a comma.
  if (pathname === '/product/900001') {
    const data = fixtureResponse({ pathname }).body.data
    return ok({ ...data, images: '', image: '', cover: '', coverImage: '' })
  }
}

export async function session(browser, { h5 = false, theme = 'default', auth = 'admin',
  scenario = 'normal', reduced = false, override, expectedAssetFailure } = {}) {
  const ctx = await browser.newContext({ serviceWorkers: 'block', acceptDownloads: false,
    reducedMotion: reduced ? 'reduce' : 'no-preference', viewport: h5 ? { width: 390, height: 844 } : { width: 1440, height: 900 } })
  const issues = new Set()
  await ctx.routeWebSocket('**/*', socket => socket.close()) // No backend connections.
  await ctx.addInitScript(({ auth, origin, token }) => {
    if (location.origin !== origin || auth === 'anonymous') return
    localStorage.setItem('delta_admin_token', token)
    localStorage.setItem('delta_admin_user', JSON.stringify({ role: auth, adminId: 900001, nickname: 'QA' }))
  }, { auth, origin, token: fixtureToken(auth) })
  await ctx.route('**/*', async route => {
    const req = route.request(), url = new URL(req.url()), p = url.pathname, method = req.method()
    try {
      assert.equal(url.origin, origin, 'external request')
      if (p.startsWith('/api/')) {
        const pathname = p.slice(4)
        let result = await override?.({ pathname, method, req })
        if (!result && method === 'GET') result = read(pathname)
        if (!result && method === 'POST' && pathname === '/admin/platform/updates/check')
          result = ok({ releases: [], connected: false }) // read/check, never install
        if (!result) {
          result = fixtureResponse({ pathname, method, theme, auth, scenario })
          assert.ok(result.known, 'missing fixture')
          if (pathname === '/admin/platform/features') result.body.data.push({
            id: 'official_wechat_notifications', entitled: scenario !== 'feature-denied', enabled: scenario !== 'feature-denied'
          })
        }
        return await route.fulfill({ status: result.status, json: result.body })
      }
      assert.equal(method, 'GET')
      await route.continue() // Only this run's ephemeral loopback origin can reach here.
    } catch (e) {
      issues.add(method + ' ' + p + ': ' + e.message.split('\n')[0])
      await route.abort().catch(() => {})
    }
  })
  const page = await ctx.newPage()
  // Navigation includes bundle startup; do not apply a UI interaction latency budget to a busy CI host.
  page.setDefaultNavigationTimeout(45000)
  page.setDefaultTimeout(12000)
  page.on('pageerror', e => issues.add('pageerror: ' + e.message))
  page.on('response', res => {
    const p = new URL(res.url()).pathname
    if (!p.startsWith('/api/') && res.status() >= 400 && !expectedAssetFailure?.(p, res.status()))
      issues.add('asset ' + res.status() + ' ' + p)
  })
  return { page, ctx, issues }
}
