// Deliberately narrow production baseline: public login pages only, no account, no mutation.
// Not a substitute for authenticated flows, payment, release validation or device testing.
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { dDrivePath } from './theme-route-inventory.mjs'

const out = dDrivePath(process.argv[2])
const temp = dDrivePath(process.env.QA_TEMP)
await Promise.all([mkdir(out, { recursive: true }), mkdir(temp, { recursive: true })])
for (const key of ['TEMP', 'TMP', 'TMPDIR']) process.env[key] = temp
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright-core')
const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE, headless: true })
const records = []
try {
  for (const [label, address] of [['520-login', 'https://kkapi.idcgov.cn/login'], ['authority-login', 'https://auth.kk520yy.com/']]) {
    const origin = new URL(address).origin
    const context = await browser.newContext({ viewport: { width: 1366, height: 768 }, serviceWorkers: 'block' })
    const record = { label, classification: 'public signed-out read-only baseline', at: new Date().toISOString(), blocked: 0 }
    try {
      await context.routeWebSocket('**/*', ws => ws.close())
      await context.route('**/*', route => {
        const req = route.request(), url = new URL(req.url())
        if (url.origin !== origin || !['GET', 'HEAD'].includes(req.method())) {
          record.blocked++
          return route.abort('blockedbyclient')
        }
        return route.continue()
      })
      const page = await context.newPage()
      record.status = (await page.goto(address, { waitUntil: 'domcontentloaded', timeout: 25000 }))?.status()
      // Never capture an authenticated screen, raw callback URL, network headers, or response bodies.
      await page.locator('input[type="password"]:visible').first().waitFor({ timeout: 15000 })
      record.timing = await page.evaluate(() => ({
        domMs: Math.round(performance.getEntriesByType('navigation')[0]?.domContentLoadedEventEnd || 0),
        loginObservedMs: Math.round(performance.now()),
        paint: performance.getEntriesByType('paint').map(e => [e.name, Math.round(e.startTime)]),
        overflow: document.documentElement.scrollWidth > innerWidth,
        liveAnimations: document.getAnimations().filter(a => a.playState === 'running').length
      }))
      await page.screenshot({ path: path.join(out, label + '.png'), animations: 'allow' })
    } catch (e) { record.failure = e.name + ': public login not verified'; process.exitCode = 1 }
    finally { records.push(record); await context.close() }
  }
} finally {
  await browser.close()
  await writeFile(path.join(out, 'public-baseline.json'), JSON.stringify(records, null, 2) + '\n')
  console.log(JSON.stringify(records))
}
