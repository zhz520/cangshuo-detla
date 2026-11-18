import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { mkdir, readFile } from 'node:fs/promises'
import { extname, resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
  ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright')
const root = resolve('delta-mp/dist/build/h5')
const output = process.env.QA_OUTPUT
if (output) await mkdir(output, { recursive: true })
const oauth = 'https://open.weixin.qq.com/connect/oauth2/authorize?state=' + '6'.repeat(64) + '#wechat_redirect'
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml' }
let theme = 'default', officialRequests = 0
const server = createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname
  if (path.startsWith('/api/')) {
    let data = {}
    if (path === '/api/system/config/site') data = { wx_pay_enabled: 'true', wx_pay_official_enabled: 'true', wx_pay_h5_enabled: 'true' }
    if (path === '/api/system/config/theme') data = { id: theme }
    if (path === '/api/order/42') data = { amount: 1, status: 'PENDING_PAYMENT', payDeadline: new Date(Date.now() + 6e5).toISOString() }
    if (path === '/api/pay/h5/42') return res.writeHead(500).end('wrong route')
    if (path === '/api/pay/wx/official/42' && req.method === 'POST') { officialRequests++; data = { authorizeUrl: oauth } }
    return res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ code: 200, data }))
  }
  try {
    const file = resolve(root, path === '/' ? 'index.html' : '.' + decodeURIComponent(path))
    if (!file.startsWith(root + sep)) return res.writeHead(404).end()
    const body = await readFile(file)
    res.writeHead(200, { 'content-type': types[extname(file)] || 'application/octet-stream' }).end(body)
  } catch { res.writeHead(404).end() }
})
server.listen(0, '127.0.0.1')
await once(server, 'listening')
const origin = 'http://127.0.0.1:' + server.address().port
try {
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
  try {
    for (const item of [
      { theme: 'default', ua: 'Mozilla/5.0 (Windows NT 10.0) Chrome/130' },
      { theme: 'dark', ua: 'Mozilla/5.0 (Windows NT 10.0) MicroMessenger/8.0 WindowsWechat' },
      { theme: 'neumorphism', ua: 'Mozilla/5.0 (Linux; Android 14) Mobile Chrome/130' },
      { theme: 'default', ua: 'Mozilla/5.0 (iPhone) Mobile MicroMessenger/8.0.60', direct: true }
    ]) {
      theme = item.theme
      const page = await browser.newPage({ userAgent: item.ua, viewport: { width: 390, height: 844 } })
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      await page.route('**/*', route => {
        const url = route.request().url()
        if (url.startsWith(origin)) return route.continue()
        if (url.startsWith('https://open.weixin.qq.com/')) return route.fulfill({ contentType: 'text/html', body: '<h1>Simulated OAuth</h1>' })
        return route.abort()
      })
      await page.goto(origin + '/#/pages/order/pay?orderId=42&amount=1')
      await page.getByText('微信支付', { exact: true }).waitFor()
      const before = officialRequests
      await page.getByText('确认支付', { exact: true }).click()
      if (item.direct) {
        await page.getByRole('heading', { name: 'Simulated OAuth' }).waitFor()
        assert.match(page.url(), /^https:\/\/open\.weixin\.qq\.com\//)
      } else {
        await page.locator('.qr-panel').waitFor()
        await page.waitForFunction(() => {
          const img = document.querySelector('.qr-image img')
          return img?.complete && img.naturalWidth === 560 && img.src.startsWith('data:image/png;base64,')
        })
        assert.equal(await page.locator('.qr-placeholder').count(), 0)
        if (theme !== 'default') assert.equal(await page.locator('html').getAttribute('data-theme'), theme)
        if (output) await page.screenshot({ path: resolve(output, '520-qr-' + theme + '.png') })
      }
      assert.equal(officialRequests, before + 1)
      assert.deepEqual(errors, [])
      console.log(item.direct ? 'mobile WeChat OAuth: PASS' : `${theme} QR: PASS`)
      await page.close()
    }
  } finally { await browser.close() }
} finally { await new Promise(resolve => server.close(resolve)) }
