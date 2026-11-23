// CI/local: node test/admin-routes-browser.mjs <local-dist-dir>
// Optional diagnosis: ROUTE_THEME=dark, ROUTE_MODE=shell|journeys, ROUTE_PATH=<exact sample path>.
// ROUTE_WORKERS=1..4 adjusts shell concurrency. ROUTE_JOURNEY filters by label.
// No host URL accepted.
import assert from 'node:assert/strict'
import path from 'node:path'
import { access } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { buildInventory } from '../../deploy/qa/theme-route-inventory.mjs'
import { legacyConfigTabs } from '../src/utils/config-sections.js'
import { origin, serveDist, session } from './admin-routes-browser-fixture.mjs'

const dist = path.resolve(process.argv[2] || fileURLToPath(new URL('../dist', import.meta.url)))
await access(path.join(dist, 'index.html'))
const mode = process.env.ROUTE_MODE || 'all'
assert.ok(['all', 'journeys', 'shell'].includes(mode))
const themes = process.env.ROUTE_THEME ? [process.env.ROUTE_THEME] : ['default', 'dark', 'neumorphism']
assert.ok(themes.every(t => ['default', 'dark', 'neumorphism'].includes(t)))
const workers = Number(process.env.ROUTE_WORKERS || 2)
assert.ok(Number.isInteger(workers) && workers >= 1 && workers <= 4)
const moduleId = process.env.QA_PLAYWRIGHT_MODULE || process.env.PLAYWRIGHT_MODULE || 'playwright'
const { chromium } = await import(path.isAbsolute(moduleId) ? pathToFileURL(moduleId).href : moduleId)
const launch = () => chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined })
const closeServer = await serveDist(dist)
const failures = []
try {
  if (mode !== 'journeys') {
    const { routes: { admin }, redirects } = await buildInventory()
    const cases = admin.map(r => {
      const h5 = r.variant === 'h5', prefix = h5 ? '/h5' : ''
      const tab = legacyConfigTabs[r.path.slice(prefix.length)]
      return { ...r, h5, expected: tab ? prefix + '/system/config' : r.samplePath,
        query: tab ? { tab } : {}, title: tab ? '系统配置' : r.title }
    })
    // Leaf inventory deliberately excludes redirect-only records. Exercise the
    // observable destinations separately, including the dynamic identity query.
    for (const { path: from, target } of redirects) {
      const dynamic = target === '[dynamic-function]'
      if (dynamic) assert.equal(from, '/user/identity-review', 'add a browser example for the new dynamic redirect')
      const leaf = cases.find(r => r.samplePath === (dynamic ? '/user/list' : target))
      assert.ok(leaf, 'redirect target missing: ' + target)
      cases.push({ ...leaf, samplePath: from + (dynamic ? '?taskId=900001' : ''),
        query: dynamic ? { tab: 'identity', userId: '900001' } : leaf.query })
    }
    const selected = process.env.ROUTE_PATH ? cases.filter(c => c.samplePath === process.env.ROUTE_PATH) : cases
    assert.ok(selected.length, 'no matching browser route samples')
    const matrix = themes.flatMap(theme => selected.map(c => ({ ...c, theme })))
    let next = 0, passed = 0
    async function sample(browser, item) {
      const { theme, h5, samplePath, expected, query, title } = item
      const { ctx, page, issues } = await session(browser, { h5, theme, auth: item.fixtureAuth })
      page.setDefaultTimeout(30000) // CI shell mounts are coverage checks, not a 12-second performance SLA.
      const label = theme + ' ' + samplePath
      try {
        await page.goto(origin + samplePath, { waitUntil: 'networkidle' })
        await page.waitForFunction(({ expected, title }) => location.pathname === expected &&
          document.title.split(' - ')[0] === title, { expected, title })
        for (const [k, v] of Object.entries(query)) assert.equal(new URL(page.url()).searchParams.get(k), v)
        assert.equal(await page.locator('html').getAttribute('data-theme') || 'default', theme)
        if (item.fixtureAuth === 'anonymous') await page.getByPlaceholder('用户名').waitFor()
        else {
          const frame = page.locator(h5 ? '.h5-page-frame' : '.layout-container .layout-page-frame')
          await frame.locator(':scope > *').first().waitFor()
          assert.ok((await frame.innerText()).trim().length > 0, 'empty routed view')
          if (item.purpose === 'intentional-403' || item.purpose === 'intentional-404') {
            await frame.getByRole('heading', { name: expected.endsWith('/403') ? '403' : '404', exact: true }).waitFor()
          } else assert.equal(await frame.locator('.error-page').count(), 0, 'rendered error view')
        }
        assert.equal(await page.locator('.app-route-error,.load-feedback--error,.site-config-error').count(), 0, 'visible load error')
        assert.deepEqual([...issues], [])
        passed++
        console.log('PASS shell:', label, '→', expected + (query.tab ? '?tab=' + query.tab : ''))
      } catch (e) {
        const detail = [label, e.message.split('\n')[0], ...issues].join(' | ')
        failures.push(detail); console.error('FAIL shell:', detail)
      } finally { await ctx.close() }
    }
    async function worker() {
      // Bound Chromium renderer lifetimes on long Windows/CI runs.
      let browser, count = 0
      try {
        while (next < matrix.length) {
          const item = matrix[next++]
          if (count++ % 16 === 0) { await browser?.close(); browser = await launch() }
          await sample(browser, item)
        }
      } finally { await browser?.close() }
    }
    await Promise.all(Array.from({ length: Math.min(workers, matrix.length) }, worker))
    console.log('SHELL: %d/%d; inventory leaves PC=%d H5=%d, redirects=%d, themes=%s, filter=%s', passed, matrix.length,
      admin.filter(r => r.variant === 'pc').length, admin.filter(r => r.variant === 'h5').length, cases.length-admin.length, themes.join(','), process.env.ROUTE_PATH || 'all')
  }
  if (mode !== 'shell') {
    const { runJourneys } = await import('./admin-routes-browser-journeys.mjs')
    const browser = await launch()
    try { failures.push(...await runJourneys(browser)) } finally { await browser.close() }
  }
} finally { await closeServer() }
if (failures.length) { console.error('%d failed cases', failures.length); process.exitCode = 1 }
