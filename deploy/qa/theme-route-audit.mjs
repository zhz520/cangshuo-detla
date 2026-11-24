import { mkdir, readFile, writeFile, appendFile, readdir, realpath } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'
import { createHash } from 'node:crypto'
import { buildInventory, repoRoot, dDrivePath, localOrigin, gitSnapshot, hashSources } from './theme-route-inventory.mjs'
import { fixtureResponse, fixtureToken, fixtureVersion } from './theme-route-fixtures.mjs'

function options(argv) {
  const result = {}
  for (let i = 0; i < argv.length; i += 2) {
    if (!argv[i].startsWith('--') || !argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error(`Expected --key value: ${argv[i]}`)
    result[argv[i].slice(2)] = argv[i + 1]
  }
  const accepted = ['admin-url', 'customer-url', 'output', 'profiles', 'themes', 'match', 'limit', 'workers', 'engine', 'guards', 'settle-ms', 'client-motion', 'customer-build']
  for (const key of Object.keys(result)) if (!accepted.includes(key)) throw new Error(`Unknown option: ${key}`)
  return result
}
export function guardCases(inventory) {
  const scenarios = []
  for (const variant of ['pc', 'h5']) {
    const prefix = variant === 'h5' ? '/h5' : ''
    const profile = inventory.profiles.find(p => p.id === (variant === 'h5' ? 'admin-h5-mobile' : 'admin-pc-wide'))
    for (const item of [
      { scenario: 'anonymous', auth: 'anonymous', path: '/product/list', expected: '/login', redirectQuery: true },
      { scenario: 'permission-denied', auth: 'admin', path: '/product/list', expected: '/403' },
      { scenario: 'role-denied', auth: 'cs', path: '/system/admin', expected: '/403' },
      { scenario: 'feature-denied', auth: 'admin', path: '/platform/income-analysis', expected: '/403' },
      { scenario: 'unauthorized', auth: 'admin', path: '/product/list', expected: '/login' },
      { scenario: 'authenticated-login', auth: 'admin', path: '/login', expected: '/dashboard' },
      { scenario: 'approval-500', auth: 'admin', path: '/player', expected: '/player', interaction: 'approval-500' },
    ]) scenarios.push({ id: `guard:${variant}:${item.scenario}`, app: 'admin', variant, path: prefix + item.path, samplePath: prefix + item.path, title: item.scenario,
      fixtureAuth: item.auth, purpose: 'guard-or-error-scenario', scenario: item.scenario, expected: prefix + item.expected,
      redirectQuery: item.redirectQuery, interaction: item.interaction, profile, theme: 'default' })
  }
  return scenarios
}
async function sourceTreeHashes() {
  const hashes = {}
  async function scan(relative) {
    for (const item of await readdir(path.join(repoRoot, relative), { withFileTypes: true })) {
      const file = `${relative}/${item.name}`
      if (item.isDirectory()) await scan(file)
      else if (/\.(vue|js|mjs|json|css|scss|html)$/.test(item.name)) hashes[file] = createHash('sha256').update(await readFile(path.join(repoRoot, file))).digest('hex')
    }
  }
  for (const dir of ['delta-admin-ui/src', 'delta-admin-ui/public', 'delta-mp/pages', 'delta-mp/pages-player', 'delta-mp/pages-cs', 'delta-mp/components', 'delta-mp/api', 'delta-mp/utils', 'delta-mp/store', 'delta-mp/styles']) await scan(dir)
  for (const file of ['delta-mp/App.vue', 'delta-mp/main.js', 'delta-mp/index.html', 'delta-mp/uni.scss']) hashes[file] = createHash('sha256').update(await readFile(path.join(repoRoot, file))).digest('hex')
  Object.assign(hashes, await hashSources())
  return hashes
}
export async function resolvePlaywright() {
  if (process.env.PLAYWRIGHT_MODULE) {
    const requested = process.env.PLAYWRIGHT_MODULE
    return import(/^[a-zA-Z]:[\\/]/.test(requested) ? pathToFileURL(requested).href : requested)
  }
  const require = createRequire(import.meta.url)
  for (const name of ['playwright', 'playwright-core']) { try { return await import(pathToFileURL(require.resolve(name)).href) } catch {} }
  throw new Error('Set PLAYWRIGHT_MODULE to an existing playwright-core/index.mjs; no installation or profile discovery is performed')
}
// Snapshot the release once: no listener, no request-controlled filesystem paths,
// no symlinks or source maps. Every case in this run sees identical bytes.
export async function customerRelease(input) {
  const root = path.join(repoRoot, 'delta-mp/dist/build/h5')
  if (path.resolve(input) !== root || await realpath(root) !== root) throw new Error('Only delta-mp/dist/build/h5 is accepted')
  const mime = { html: 'text/html; charset=utf-8', js: 'application/javascript', css: 'text/css',
    png: 'image/png', svg: 'image/svg+xml', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp',
    ico: 'image/x-icon', wav: 'audio/wav', mp3: 'audio/mpeg', woff: 'font/woff', woff2: 'font/woff2', ttf: 'font/ttf' }
  const files = new Map(), hash = createHash('sha256')
  async function collect(dir, prefix) {
    const entries = await readdir(dir, { withFileTypes: true })
    for (const ent of entries.sort((a,b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)) {
      const key = prefix + '/' + ent.name, file = path.join(dir, ent.name)
      if (ent.isSymbolicLink()) throw new Error('Symlink in H5 build: ' + key)
      if (ent.isDirectory()) { await collect(file, key); continue }
      const contentType = mime[path.extname(key).slice(1)]
      if (!ent.isFile() || !contentType || !(key === '/index.html' || /^\/(assets|static|pages-player|pages-cs)\//.test(key))) continue
      const body = await readFile(file)
      files.set(key, { contentType, body })
      hash.update(JSON.stringify([key, createHash('sha256').update(body).digest('hex')]))
    }
  }
  await collect(root, '')
  const index = files.get('/index.html')?.body
  if (!index || index.toString().includes('interactive-widget')) throw new Error('Missing or obsolete release index.html')
  return { files, identity: { root, staticFiles: files.size, sha256: hash.digest('hex'), indexSha256: createHash('sha256').update(index).digest('hex') } }
}
export async function installIsolation(context, { origin, auth, theme, scenario = 'normal', release }, evidence) {
  if (typeof context.routeWebSocket !== 'function') throw new Error('A Playwright runtime with routeWebSocket is required for isolation')
  await context.routeWebSocket('**/*', socket => {
    const u = new URL(socket.url())
    evidence.webSockets.push({ origin: u.origin, path: u.pathname, disposition: 'closed-without-connecting-to-server' })
    socket.close({ code: 1000, reason: 'SYNTHETIC_QA_ISOLATION' })
  })
  await context.addInitScript(({ origin, auth, token }) => {
    if (location.origin !== origin) return
    // New context only; do not read or export existing browser authentication.
    globalThis.__CANGSHUO_INSTANCE__ = { storagePrefix: 'qa_route_', adminTokenKey: 'qa_admin_token', adminUserKey: 'qa_admin_user' }
    if (auth === 'anonymous') return
    if (auth === 'admin' || auth === 'cs') {
      localStorage.setItem('qa_admin_token', token)
      localStorage.setItem('qa_admin_user', JSON.stringify({ id: 900001, role: auth, nickname: '合成测试工作人员', username: 'synthetic-qa' }))
    } else {
      localStorage.setItem('qa_route_user_token', token)
      if (auth === 'player') localStorage.setItem('qa_route_player_token', token)
      localStorage.setItem('qa_route_app_role', auth)
    }
  }, { origin, auth, token: fixtureToken(auth) })
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url())
    if (!['http:', 'https:'].includes(url.protocol)) return route.continue()
    const method = request.method()
    if (url.origin !== origin) {
      evidence.blockedNetwork.push({ origin: url.origin, path: url.pathname, method, resource: request.resourceType() })
      return route.abort('blockedbyclient')
    }
    if (url.pathname === '/instance-config.js') return route.fulfill({ status: 200, contentType: 'application/javascript', body: '/* SYNTHETIC QA: runtime config supplied by context init script */' })
    // Uni source modules also live under /api/*.js in dev mode. They are scripts, not business requests.
    const isApi = url.pathname.startsWith('/__qa_api__/') || (url.pathname.startsWith('/api/') && !['script', 'stylesheet'].includes(request.resourceType())) || ['fetch', 'xhr'].includes(request.resourceType())
    if (isApi) {
      const response = fixtureResponse({ pathname: url.pathname, method, auth, theme, scenario })
      const query = Object.fromEntries([...url.searchParams].filter(([key]) => !/token|secret|password|authorization/i.test(key)))
      evidence.api.push({ path: url.pathname, query, method, status: response.status, known: response.known, fixtureState: response.state,
        fixtureAuthorizationMatched: request.headers().authorization === `Bearer ${fixtureToken(auth)}` })
      if (!response.known) evidence.fixtureGaps.push({ path: url.pathname, method, state: response.state })
      return route.fulfill({ status: response.status, contentType: 'application/json', headers: { 'X-QA-Fixture': fixtureVersion, 'Cache-Control': 'no-store' }, body: JSON.stringify(response.body) })
    }
    if (method !== 'GET' && method !== 'HEAD') {
      evidence.blockedNetwork.push({ origin: url.origin, path: url.pathname, method, resource: request.resourceType() })
      return route.abort('blockedbyclient')
    }
    if (release) {
      let key
      try { key = decodeURIComponent(url.pathname) } catch { return route.fulfill({status:400, body:''}) }
      const asset = release.files.get(key === '/' ? '/index.html' : key)
      if (!asset) return route.fulfill({status:404, body:'Missing release asset'})
      return route.fulfill({status:200, contentType:asset.contentType, body:method === 'HEAD' ? '' : asset.body})
    }
    return route.continue()
  })
}
async function layoutSnapshot(page) {
  return page.evaluate(() => {
    const visible = el => { const r = el.getBoundingClientRect(), s = getComputedStyle(el); return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden' }
    const describe = el => `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.') : ''}`
    const box = selector => { const el = document.querySelector(selector); if (!el || !visible(el)) return null; const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height, bottom: r.bottom } }
    const overflowCandidates = [], scrollSurfaces = []
    for (const el of [...document.querySelectorAll('body *')].slice(0, 8000)) {
      if (!visible(el)) continue
      const r = el.getBoundingClientRect(), css = getComputedStyle(el)
      if (el.scrollWidth > el.clientWidth + 2 && el.clientWidth > 10 && ['auto', 'scroll'].includes(css.overflowX)) scrollSurfaces.push({ selector: describe(el), client: el.clientWidth, scroll: el.scrollWidth })
      if ((r.right > innerWidth + 2 || r.left < -2) && r.bottom > 0 && r.top < innerHeight && overflowCandidates.length < 16) {
        let ancestor = el.parentElement, locallyClipped = false
        while (ancestor && ancestor !== document.body) { if (['auto', 'scroll', 'hidden', 'clip'].includes(getComputedStyle(ancestor).overflowX)) { locallyClipped = true; break } ancestor = ancestor.parentElement }
        if (!locallyClipped) overflowCandidates.push({ selector: describe(el), left: r.left, right: r.right, width: r.width })
      }
    }
    const frame = document.querySelector('.layout-page-frame')
    const customerBody = [...document.querySelectorAll('uni-page-body')].find(visible)
    const root = frame || customerBody || document.querySelector('#app')
    const text = root?.innerText?.trim() || ''
    const nav = box('.h5-bottom-nav'), main = box('.h5-main')
    const mainElement = document.querySelector('.h5-main')
    const bottomPadding = mainElement ? parseFloat(getComputedStyle(mainElement).paddingBottom) : 0
    return { viewport: { width: innerWidth, height: innerHeight }, documentWidth: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth),
      theme: document.documentElement.getAttribute('data-theme') || 'default', pageTextLength: text.length, textExcerpt: text.slice(0, 550),
      contentChildren: root?.children.length || 0, pageFramePresent: Boolean(frame), customerBodyPresent: Boolean(customerBody),
      shells: { pc: Boolean(document.querySelector('.layout-container')), h5: Boolean(document.querySelector('.h5-shell')) },
      header: box('.h5-header'), main, bottomNav: nav,
      bottomPadding, h5MainClearsBottomNav: !nav || !main || main.bottom <= nav.y + 3 || bottomPadding >= nav.height,
      overflowCandidates, scrollSurfaces: scrollSurfaces.slice(0, 15),
      brokenImages: [...document.images].filter(el => visible(el) && el.complete && el.naturalWidth === 0).slice(0, 10).map(el => ({ selector: describe(el), sourceType: el.src.startsWith('data:') ? 'data' : 'resource' })),
      pageFeedback: [...document.querySelectorAll('.load-feedback, .app-route-error, .read-error, .state-error')].filter(visible).map(el => el.innerText?.trim()).filter(Boolean).slice(0, 8),
      busyIndicators: document.querySelectorAll('[aria-busy="true"], .el-loading-mask, .app-loading, .state-skeleton').length }
  })
}
async function approvalFailure(page, evidence) {
  await page.getByRole('button', { name: '通过', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: '通过入驻审核', exact: true })
  await dialog.waitFor({ state: 'visible' })
  await dialog.locator('.el-select').click()
  await page.getByRole('option', { name: '合成测试等级 1', exact: true }).click()
  const submit = dialog.getByRole('button', { name: '确认通过', exact: true })
  await submit.click()
  await page.getByText('合成审核接口错误（500）', { exact: true }).first().waitFor({ state: 'visible', timeout: 5000 })
  await page.waitForFunction(() => !document.querySelector('.el-dialog button.is-loading'))
  const request = evidence.api.filter(item => item.method === 'PUT' && item.path.endsWith('/admin/player/900001/approve'))
  return { requestCount: request.length, requestedLevelId: request[0]?.query.levelId, status: request[0]?.status,
    errorVisible: await page.getByText('合成审核接口错误（500）', { exact: true }).first().isVisible(), dialogRetained: await dialog.isVisible(),
    submittingCleared: !(await submit.getAttribute('class'))?.includes('is-loading'),
    pendingRowRetained: await page.locator('.el-table__body-wrapper').getByText('待审核', { exact: true }).count() > 0,
    noSuccessMessage: await page.getByText('已通过「合成测试接单员」的入驻申请', { exact: true }).count() === 0,
    isolation: 'HTTP 500 response fulfilled in browser; no backend approval request sent' }
}
const slug = value => value.replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-|-$/g, '').slice(0, 180)
async function runCase(browser, item, config) {
  const startedAt = new Date().toISOString(), start = Date.now()
  const origin = item.app === 'admin' ? config.adminOrigin : config.customerOrigin
  const evidence = { api: [], fixtureGaps: [], blockedNetwork: [], webSockets: [], pageErrors: [], consoleErrors: [], consoleWarnings: [], requestFailures: [], httpErrors: [] }
  let context
  const newContext = () => browser.newContext({ viewport: item.profile.viewport, deviceScaleFactor: 1, isMobile: Boolean(item.profile.mobile), hasTouch: Boolean(item.profile.mobile),
    locale: 'zh-CN', timezoneId: 'Asia/Shanghai', colorScheme: 'light', reducedMotion: item.app === 'customer' && config.clientMotion === 'normal' ? 'no-preference' : 'reduce', serviceWorkers: 'block' })
  const result = { id: item.id, app: item.app, route: item.path, title: item.title, purpose: item.purpose, profile: item.profile.id, viewport: item.profile.viewport,
    theme: item.theme, fixtureAuth: item.fixtureAuth, fixtureVersion, scenario: item.scenario || 'normal', startedAt, evidence, issues: [] }
  const file = `${String(item.sequence).padStart(4, '0')}-${slug(item.id)}-${item.profile.id}-${item.theme}.png`
  try {
    let page
    // WebKit can lose a newly-created blank process under Windows memory pressure.
    // Only retry before goto: do not replay a navigation, API call or payment action.
    for (let attempt = 1; attempt <= 2; attempt++) {
      context = await newContext()
      await installIsolation(context, { origin, auth: item.fixtureAuth, theme: item.theme, scenario: item.scenario, release: item.app === 'customer' ? config.release : null }, evidence)
      if (item.app === 'customer' && config.clientMotion) await context.addInitScript(() => {
        window.__qaRouteAnimations = []
        window.__qaOtherAnimations = []
        document.addEventListener('animationstart', e => {
          if (!e.animationName.startsWith('delta-page-') && window.__qaOtherAnimations.length < 64)
            window.__qaOtherAnimations.push({name:e.animationName, atMs:Math.round(performance.now()), tag:e.target.tagName})
          if (e.animationName.startsWith('delta-page-')) window.__qaRouteAnimations.push({
            name: e.animationName, atMs: Math.round(performance.now()), owner: e.target.getAttribute('data-delta-enter')
          })
        }, true)
      })
      try { page = await context.newPage(); break } catch (error) {
        if (attempt !== 1 || !browser.isConnected() || !/closed|crash/i.test(error.message)) throw error
        result.blankPageRetry = { phase: 'before-navigation', error: error.message.split('\n')[0] }
        await context.close()
        context = null
      }
    }
    page.setDefaultTimeout(10000)
    page.on('pageerror', error => evidence.pageErrors.push(error.message))
    page.on('console', message => { if (message.type() === 'error') evidence.consoleErrors.push(message.text()); if (message.type() === 'warning') evidence.consoleWarnings.push(message.text()) })
    page.on('requestfailed', request => { if (request.failure()?.errorText !== 'net::ERR_ABORTED') evidence.requestFailures.push({ path: new URL(request.url()).pathname, error: request.failure()?.errorText }) })
    page.on('response', response => { if (response.status() >= 400) evidence.httpErrors.push({ path: new URL(response.url()).pathname, status: response.status() }) })
    await page.goto(origin + item.samplePath, { waitUntil: 'domcontentloaded', timeout: 45000 })
    const expected = item.expected || (item.app === 'admin' ? item.samplePath : item.path)
    // A real empty reviews response renders four Chinese characters. Check that
    // exact state (and only that route); do not lower the readiness bar globally.
    const emptyReview = item.path === '/pages-player/review/list' ? '暂无评价' : null
    await page.waitForFunction(({ expected, app, emptyReview }) => {
      const route = app === 'customer' ? location.hash.slice(1).split('?')[0] : location.pathname
      const text = document.querySelector(app === 'customer' ? 'uni-page-body' : '#app')?.innerText?.trim()
      return route === expected && (text?.length > 5 || (emptyReview && text === emptyReview))
    }, { expected, app: item.app, emptyReview }, { timeout: 25000 }).catch(error => { result.readinessError = error.message.split('\n')[0] })
    if (item.app === 'admin' && !expected.endsWith('/login')) {
      await page.locator('.layout-page-frame > *').first().waitFor({ state: 'visible', timeout: 15000 }).catch(error => { result.contentWaitError = error.message.split('\n')[0] })
    }
    await page.waitForFunction(() => !document.querySelector('.layout-main[aria-busy="true"], .app-loading, .state-skeleton, .el-loading-mask'), null, { timeout: 8000 }).catch(() => { result.busyWaitTimedOut = true })
    await page.waitForTimeout(config.settleMs)
    if (item.interaction === 'approval-500') result.interaction = await approvalFailure(page, evidence)
    result.finalPath = new URL(page.url()).pathname + new URL(page.url()).hash
    const actual = item.app === 'customer' ? new URL(page.url()).hash.slice(1).split('?')[0] : new URL(page.url()).pathname
    result.routeReached = actual === expected
    if (item.redirectQuery) result.redirectPreserved = new URL(page.url()).searchParams.get('redirect') === item.samplePath
    result.layout = await layoutSnapshot(page)
    if (item.app === 'customer' && item.path === '/pages-player/deposit/refund') {
      result.refundEmptyStates = await page.locator('uni-page-body .empty-state').evaluateAll(states => states.map(state => {
        const picture = state.querySelector('.app-image')
        const img = picture?.querySelector('img')
        const css = picture?.querySelector('div')
        const source = img?.currentSrc || img?.getAttribute('src') ||
          (css && getComputedStyle(css).backgroundImage.match(/url\(["']?([^"')]+)["']?\)/)?.[1])
        return { text: state.querySelector('.empty-text')?.textContent?.trim(),
          asset: source ? decodeURIComponent(new URL(source, location.href).pathname) : null,
          loaded: !!picture && !picture.matches('.image-loading,.image-failed') &&
            (img?.currentSrc ? img.complete && img.naturalWidth > 0 : !!source) }
      }))
      const expected = ['暂无已缴押金', '暂无退款申请']
      if (result.refundEmptyStates.length !== expected.length || !expected.every(text =>
        result.refundEmptyStates.some(s => s.text === text && s.asset === '/static/icons/空空如也.svg' && s.loaded)))
        result.issues.push('refund-empty-asset-not-confirmed')
    }
    if (item.app === 'customer' && item.path === '/pages/order/pay') {
      const pay = page.locator('uni-page-body .pay-page')
      // trial checks visibility, hit testing and actionability without dispatching a click.
      const confirm = pay.locator('.btn-pay:not(.disabled)')
      const unobstructed = await confirm.click({trial:true,timeout:4000}).then(() => true, () => false)
      result.payment = {
        unobstructed,
        countdownVisible: await pay.locator('.countdown:not(.expired)').isVisible(),
        walletRead: evidence.api.some(r => r.method === 'GET' && r.path.endsWith('/user/wallet') && r.status === 200 && r.known),
        hasAmount: (await pay.locator('.amount-area').innerText()).includes(item.query.amount),
        confirmVisible: await pay.getByText('确认支付', { exact: true }).isVisible(),
        orderRead: evidence.api.some(r => r.method === 'GET' && r.path.endsWith('/order/' + item.query.orderId) && r.known && r.status === 200),
        readOnly: evidence.api.every(r => r.method === 'GET')
      }
      if (!Object.values(result.payment).every(Boolean)) result.issues.push('payment-page-not-confirmed')
    }
    result.screenshot = `screenshots/${file}`
    await page.screenshot({ path: path.join(config.output, result.screenshot), fullPage: true, animations: item.app === 'customer' && config.clientMotion ? 'allow' : 'disabled', timeout: 20000 })
    if (item.app === 'customer' && config.clientMotion) {
      result.motion = { mode: config.clientMotion, ...await page.evaluate(() => ({
        events: window.__qaRouteAnimations, otherEvents: window.__qaOtherAnimations, observedUntilMs: Math.round(performance.now()), reduced: matchMedia('(prefers-reduced-motion: reduce)').matches,
        targets: [...document.querySelectorAll('[data-delta-enter]')].map(el => ({
          tag: el.tagName, className: el.className, owner: el.getAttribute('data-delta-enter'), animation: getComputedStyle(el).animationName
        }))
      })) }
      if (result.motion.reduced !== (config.clientMotion === 'reduce')) result.issues.push('motion-preference-mismatch')
      if (result.motion.events.length !== (config.clientMotion === 'normal' ? 1 : 0)) result.issues.push('unexpected-route-animation-count')
    }
    const feedback = result.layout.pageFeedback
    if (!result.routeReached) result.issues.push('unexpected-route-or-guard-redirect')
    if (result.readinessError || result.contentWaitError || (result.layout.pageTextLength < 6 && result.layout.textExcerpt !== emptyReview) || !result.layout.contentChildren) result.issues.push('content-not-confirmed')
    if (result.layout.theme !== item.theme) result.issues.push('theme-not-applied')
    if (result.layout.documentWidth > item.profile.viewport.width + 2) result.issues.push('document-horizontal-overflow')
    if (result.layout.brokenImages.length) result.issues.push('broken-visible-image')
    if (evidence.fixtureGaps.length) result.issues.push('fixture-gap')
    if (evidence.blockedNetwork.length) result.issues.push('blocked-unexpected-network')
    if (evidence.pageErrors.length) result.issues.push('page-script-error')
    result.expectedConsoleErrors = evidence.consoleErrors.filter(message => message.startsWith('[vite] failed to connect to websocket.') || (item.scenario === 'approval-500' && message === 'Failed to load resource: the server responded with a status of 500 (Internal Server Error)'))
    result.unexpectedConsoleErrors = evidence.consoleErrors.filter(message => !result.expectedConsoleErrors.includes(message))
    if (result.unexpectedConsoleErrors.length) result.issues.push('console-error')
    if (feedback.length) result.issues.push('page-load-feedback-needs-review')
    if (result.busyWaitTimedOut || result.layout.busyIndicators) result.issues.push('loading-not-settled')
    if (item.app === 'admin' && !actual.endsWith('/login')) {
      const rightShell = item.variant === 'h5' ? result.layout.shells.h5 && !result.layout.shells.pc : result.layout.shells.pc && !result.layout.shells.h5
      if (!rightShell) result.issues.push('incorrect-admin-shell')
      if (item.variant === 'h5' && !result.layout.h5MainClearsBottomNav) result.issues.push('h5-main-bottom-nav-overlap')
    }
    if (item.redirectQuery && !result.redirectPreserved) result.issues.push('login-redirect-lost')
    if (result.interaction && !(result.interaction.requestCount === 1 && result.interaction.requestedLevelId === '1' && result.interaction.status === 500 && result.interaction.errorVisible && result.interaction.dialogRetained && result.interaction.submittingCleared && result.interaction.pendingRowRetained && result.interaction.noSuccessMessage)) result.issues.push('approval-500-recovery-failed')
    result.authObserved = evidence.api.some(request => request.fixtureAuthorizationMatched)
    if (item.fixtureAuth !== 'anonymous' && !result.authObserved) result.issues.push('fixture-auth-not-observed')
  } catch (error) {
    result.error = error.message
    result.issues.push('harness-or-navigation-error')
    const page = context?.pages()[0]
    if (page) {
      result.finalPath = new URL(page.url()).pathname + new URL(page.url()).hash
      result.screenshot = `screenshots/${file}`
      await page.screenshot({ path: path.join(config.output, result.screenshot), fullPage: true, animations: 'disabled', timeout: 10000 }).catch(() => { result.screenshot = null })
    }
  } finally {
    await context?.close()
    result.durationMs = Date.now() - start
    result.finishedAt = new Date().toISOString()
    result.status = result.issues.length ? 'findings' : 'fixture-render-checks-passed'
  }
  return result
}
function summarize(results) {
  const groups = {}
  for (const item of results) {
    const key = `${item.profile}/${item.theme}`
    const g = groups[key] ||= { attempted: 0, reached: 0, screenshots: 0, checksPassed: 0, findings: 0 }
    g.attempted++; g.reached += Number(Boolean(item.routeReached)); g.screenshots += Number(Boolean(item.screenshot)); g.checksPassed += Number(!item.issues.length); g.findings += Number(Boolean(item.issues.length))
  }
  const issues = {}
  for (const result of results) for (const issue of result.issues) issues[issue] = (issues[issue] || 0) + 1
  return { attempted: results.length, reached: results.filter(r => r.routeReached).length, screenshots: results.filter(r => r.screenshot).length, checksPassed: results.filter(r => !r.issues.length).length,
    uniqueCustomerRoutes: new Set(results.filter(r => r.app === 'customer').map(r => r.route)).size,
    uniqueAdminPcLeaves: new Set(results.filter(r => r.app === 'admin' && r.profile.startsWith('admin-pc') && r.purpose !== 'guard-or-error-scenario').map(r => r.route)).size,
    uniqueAdminH5Leaves: new Set(results.filter(r => r.app === 'admin' && r.profile.startsWith('admin-h5') && r.purpose !== 'guard-or-error-scenario').map(r => r.route)).size, groups, issues }
}
const escapeHtml = text => String(text ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
async function report(config, data) {
  await writeFile(path.join(config.output, 'results.json'), JSON.stringify(data, null, 2) + '\n')
  const rows = data.results.map(r => `<tr><td>${escapeHtml(r.id)}</td><td>${escapeHtml(r.profile)}<br>${escapeHtml(r.theme)}</td><td>${escapeHtml(r.finalPath)}</td><td>${escapeHtml(r.issues.join(', ') || r.status)}</td><td>${r.screenshot ? `<a href="${escapeHtml(r.screenshot)}"><img loading="lazy" src="${escapeHtml(r.screenshot)}" width="160" alt="${escapeHtml(r.id)}"></a>` : '—'}</td></tr>`).join('')
  await writeFile(path.join(config.output, 'index.html'), `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>隔离路由审计</title><style>body{font:14px system-ui;margin:24px;background:#f7f8fa;color:#18202b}table{width:100%;border-collapse:collapse;background:white}td,th{border:1px solid #ddd;padding:8px;text-align:left;vertical-align:top}img{max-height:180px;object-fit:contain}pre{white-space:pre-wrap}input{padding:10px;width:80%;margin:14px 0}</style><h1>合成数据隔离路由审计</h1><p>浏览器证据来自本地真实运行，身份与全部 API 数据均为 synthetic fixture。此报告不代表生产验证或业务验收。</p><pre>${escapeHtml(JSON.stringify(data.summary, null, 2))}</pre><p>源码变化文件：${data.sourceDrift.length}；未执行矩阵项：${data.unattempted.length}。完整细节见 <a href="results.json">results.json</a>。</p><input id="filter" placeholder="按路径、视口、主题或问题过滤"><table><thead><tr><th>路由/场景</th><th>视口/主题</th><th>实际地址</th><th>结果/问题</th><th>截图</th></tr></thead><tbody>${rows}</tbody></table><script>document.querySelector('#filter').addEventListener('input',event=>{for(const row of document.querySelectorAll('tbody tr'))row.hidden=!row.innerText.toLowerCase().includes(event.target.value.toLowerCase())})</script></html>`)
  const text = ['# 隔离路由审计实际结果', '', `执行：${data.startedAt} — ${data.finishedAt}`, '',
    '所有身份、业务响应、授权与 500 均为合成 fixture；截图、JS/console、DOM 尺寸、地址与请求计数来自此次本地浏览器执行。未接触生产数据。', '',
    `浏览器：${data.runtime.engine} ${data.runtime.browserVersion} / Playwright ${data.runtime.playwrightVersion}`,
    `尝试 ${data.summary.attempted}；到达预期地址 ${data.summary.reached}；截图 ${data.summary.screenshots}；自动检查无发现 ${data.summary.checksPassed}。`,
    `客户注册页 ${data.summary.uniqueCustomerRoutes}/${data.inventory.counts.customerRegistered}；PC 叶路由 ${data.summary.uniqueAdminPcLeaves}/${data.inventory.counts.adminPcLeaves}；H5 叶路由 ${data.summary.uniqueAdminH5Leaves}/${data.inventory.counts.adminH5Leaves}。`, '',
    '| 矩阵 | 尝试 | 到达 | 截图 | 无发现 | 有发现 |', '| --- | ---: | ---: | ---: | ---: | ---: |',
    ...Object.entries(data.summary.groups).map(([key,g]) => `| ${key} | ${g.attempted} | ${g.reached} | ${g.screenshots} | ${g.checksPassed} | ${g.findings} |`), '',
    `源码变化：${data.sourceDrift.length ? data.sourceDrift.join(', ') : '前后哈希一致'}。`, '', '## 自动发现计数', '', ...Object.entries(data.summary.issues).map(([key,count]) => `- ${key}: ${count}`), '',
    '## 证据边界与未测试项', '', ...data.inventory.limitations.map(s => '- ' + s),
    `- 本次未执行矩阵项：${data.unattempted.length}，详见 results.json 的 unattempted。`,
    '- 页面可达、空数据和错误页面均分别记录；自动检查通过仍需逐张人工视觉复核。局部表格横向滚动只记录，不等同于文档溢出。', '',
    '## 审核 500 场景（全部模拟）', '', ...data.results.filter(r => r.scenario === 'approval-500').map(r => `- ${r.profile}: ${JSON.stringify(r.interaction || { error: r.error })}; issues=${r.issues.join(',') || 'none'}`), '']
  await writeFile(path.join(config.output, 'summary.md'), text.join('\n'))
}
export async function main(argv = process.argv.slice(2)) {
  const args = options(argv)
  const outputRoot = dDrivePath(args.output || 'D:/CodexData/outputs/delta-theme-route-audit')
  const output = dDrivePath(path.join(outputRoot, new Date().toISOString().replace(/[:.]/g, '-')))
  const temp = dDrivePath(process.env.QA_TEMP || 'D:/CodexData/temp/delta-theme-route-audit')
  for (const key of ['TEMP', 'TMP', 'TMPDIR']) process.env[key] = temp
  process.env.PLAYWRIGHT_BROWSERS_PATH ||= 'D:/CodexData/temp/playwright-browsers'
  await mkdir(path.join(output, 'screenshots'), { recursive: true })
  await mkdir(temp, { recursive: true })
  const config = { output, adminOrigin: localOrigin(args['admin-url'] || 'http://127.0.0.1:5317'), customerOrigin: localOrigin(args['customer-url'] || 'http://127.0.0.1:5318'), settleMs: Number(args['settle-ms'] || 350) }
  if (args['customer-build']) {
    if (args['customer-url']) throw new Error('customer-build and customer-url are mutually exclusive')
    config.release = await customerRelease(args['customer-build'])
  }
  config.clientMotion = args['client-motion']
  if (config.clientMotion && !['normal', 'reduce'].includes(config.clientMotion)) throw new Error('client-motion must be normal or reduce')
  const inventory = await buildInventory(), sourceBefore = await sourceTreeHashes()
  const themeList = (args.themes || inventory.themes.join(',')).split(',')
  if (themeList.some(theme => !inventory.themes.includes(theme))) throw new Error('Unsupported theme')
  const selectedProfiles = (args.profiles || inventory.profiles.map(p => p.id).join(',')).split(',')
  if (selectedProfiles.some(profile => !inventory.profiles.some(p => p.id === profile))) throw new Error('Unsupported profile')
  const allCases = inventory.profiles.flatMap(profile => [...inventory.routes[profile.app]].filter(route => profile.app === 'customer' || route.variant === profile.variant).flatMap(route => inventory.themes.map(theme => ({ ...route, profile, theme }))))
  const filter = args.match ? new RegExp(args.match) : null
  let cases = allCases.filter(item => selectedProfiles.includes(item.profile.id) && themeList.includes(item.theme) && (!filter || filter.test(item.id)))
  if (args.limit) cases = cases.slice(0, Number(args.limit))
  if (args.guards !== 'off') cases.push(...guardCases(inventory).filter(item => selectedProfiles.includes(item.profile.id) && themeList.includes(item.theme) && (!filter || filter.test(item.id))))
  cases.forEach((item, i) => { item.sequence = i + 1 })
  if (!cases.length) throw new Error('No selected cases')
  const key = item => `${item.id}|${item.profile.id}|${item.theme}`
  const planned = new Set(cases.map(key))
  const unattempted = allCases.filter(item => !planned.has(key(item))).map(item => ({ id: item.id, profile: item.profile.id, theme: item.theme, reason: 'not selected for this run' }))
  const startedAt = new Date().toISOString(), gitBefore = gitSnapshot()
  const playwright = await resolvePlaywright(), engine = args.engine || 'chromium'
  if (!['chromium', 'webkit', 'firefox'].includes(engine)) throw new Error('Unsupported browser engine')
  const browser = await playwright[engine].launch({ headless: true, downloadsPath: path.join(temp, 'downloads'), tracesDir: path.join(temp, 'traces') })
  const runtime = { engine, browserVersion: browser.version(), playwrightVersion: 'see PLAYWRIGHT_MODULE installation', module: process.env.PLAYWRIGHT_MODULE || 'resolved package' }
  if (process.env.PLAYWRIGHT_MODULE && /^[a-zA-Z]:[\\/]/.test(process.env.PLAYWRIGHT_MODULE)) { try { runtime.playwrightVersion = JSON.parse(await readFile(path.join(path.dirname(process.env.PLAYWRIGHT_MODULE), 'package.json'), 'utf8')).version } catch {} }
  console.log(JSON.stringify({ output, cases: cases.length, runtime, profiles: selectedProfiles, themes: themeList }))
  const results = [], workers = Number(args.workers || 4)
  if (!Number.isInteger(workers) || workers < 1 || workers > 8) throw new Error('workers must be 1..8')
  let next = 0
  try {
    await Promise.all(Array.from({ length: Math.min(workers, cases.length) }, async () => {
      while (next < cases.length) {
        const item = cases[next++]
        const result = await runCase(browser, item, config)
        results.push(result)
        await appendFile(path.join(output, 'cases.ndjson'), JSON.stringify(result) + '\n')
        console.log(`${results.length}/${cases.length} ${item.id} ${item.profile.id}/${item.theme}: ${result.issues.join(',') || result.status}`)
      }
    }))
  } finally {
    await browser.close()
    const sourceAfter = await sourceTreeHashes()
    const sourceDrift = [...new Set([...Object.keys(sourceBefore), ...Object.keys(sourceAfter)])].filter(file => sourceBefore[file] !== sourceAfter[file])
    const data = { schemaVersion: 1, classification: 'BROWSER EVIDENCE OF SYNTHETIC LOCAL FIXTURES; NOT PRODUCTION OR BUSINESS ACCEPTANCE',
      startedAt, finishedAt: new Date().toISOString(), customerBuild: config.release?.identity, origins: { admin: config.adminOrigin, customer: config.customerOrigin }, runtime, gitBefore, gitAfter: gitSnapshot(),
      sourceBefore, sourceAfter, sourceDrift, inventory, summary: summarize(results), unattempted, results: results.sort((a,b) => a.id.localeCompare(b.id) || a.profile.localeCompare(b.profile) || a.theme.localeCompare(b.theme)) }
    await report(config, data)
    await writeFile(path.join(outputRoot, 'latest.json'), JSON.stringify({ output, summary: data.summary }, null, 2) + '\n')
    console.log(JSON.stringify({ output, summary: data.summary, sourceDrift }, null, 2))
    if (results.some(result => result.issues.length)) process.exitCode = 1
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main()
