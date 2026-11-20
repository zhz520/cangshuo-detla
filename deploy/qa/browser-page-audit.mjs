import { spawn } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const base = (process.env.AUDIT_BASE || 'https://kk.idcgov.cn').replace(/\/$/, '')
const chromePath = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const outputDir = process.env.AUDIT_OUTPUT || 'D:\\CodexData\\outputs\\kk520-page-audit'
const viewport = { width: 390, height: 844, deviceScaleFactor: 1, mobile: true }
const expectAuthenticated = process.env.AUDIT_EXPECT_AUTH === '1'
const surface = process.env.AUDIT_SURFACE || 'h5'
const adminMode = surface === 'admin' || surface === 'adminH5'
const h5UserMode = surface === 'h5user'
const auditBase = adminMode ? 'https://kkapi.idcgov.cn' : base
const auditOutputDir = adminMode
  ? (process.env.AUDIT_OUTPUT || 'D:/CodexData/outputs/kk520-admin-page-audit')
  : h5UserMode ? 'D:\\CodexData\\outputs\\kk520-user-page-audit' : outputDir
const auditViewport = adminMode && surface === 'admin'
  ? { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false }
  : viewport
const requireAuthenticated = expectAuthenticated || adminMode || h5UserMode
const adminTokenFile = process.env.AUDIT_ADMIN_TOKEN_FILE || 'D:\\CodexData\\secrets\\kk520-admin-audit-token.json'
const userTokenFile = process.env.AUDIT_USER_TOKEN_FILE || 'D:\\CodexData\\secrets\\kk520-user-audit-token.json'
const h5PublicRoutes = [
  '/pages/index/index',
  '/pages/category/index',
  '/pages/product/list?focus=1',
  '/pages/product/detail?id=1',
  '/pages/players/index',
  '/pages/players/detail?id=89',
  '/pages/chat/list',
  '/pages/mine/index',
  '/pages/message/index',
  '/pages/agreement/user',
  '/pages/agreement/privacy',
  '/pages/agreement/about',
  '/pages/display/index?tab=rank',
  '/pages/membership/index',
  '/pages/coupons/index',
  '/pages/invites/index',
  '/pages/order/list',
  '/pages/wallet/index',
  '/pages/complaint/list'
]
const adminEntries = [
  ['/dashboard', '仪表盘'],
  ['/product/list', '商品列表'],
  ['/product/category', '分类管理'],
  ['/product/recommend-category', '热门分类管理'],
  ['/product/form-fields', '下单字段配置'],
  ['/order/list', '订单列表'],
  ['/user/list', '用户列表'],
  ['/user/spending-rank', '消费榜单'],
  ['/player', '打手管理'],
  ['/player-level', '打手等级分类'],
  ['/rewards', '打赏记录'],
  ['/invitations', '入驻邀请'],
  ['/material-review', '主页素材审核'],
  ['/staff-shifts', '上下班管理'],
  ['/privacy-requests', '隐私与注销申请'],
  ['/settlements', '自动分账台账'],
  ['/marketing/coupons', '优惠券管理'],
  ['/marketing/invites', '邀请奖励'],
  ['/marketing/membership', '会员等级'],
  ['/statistics/income-daily', '收益日报'],
  ['/complaint', '投诉处理'],
  ['/refund', '退款审批'],
  ['/replace', '换人申请'],
  ['/relay', '接力审核'],
  ['/fulfillment-review', '履约审核'],
  ['/withdraw', '提现管理'],
  ['/chat/service', '在线回复'],
  ['/chat/quick-reply', '快捷回复'],
  ['/chat/routing', '客服分配'],
  ['/chat/record', '聊天记录'],
  ['/platform/features', '功能商城'],
  ['/platform/themes', '主题商店'],
  ['/platform/updates', '系统更新'],
  ['/system/wechat-notices', '通知记录'],
  ['/system/email-notifications', '邮件通知'],
  ['/system/roles', '角色与权限'],
  ['/system/admin', '管理员管理'],
  ['/system/notice', '公告管理'],
  ['/system/banner', '轮播图管理'],
  ['/system/reward-grab', '打赏与抢单设置'],
  ['/system/content', '内容页面'],
  ['/system/player-layout', '打手列表布局'],
  ['/system/config', '系统配置'],
  ['/system/recharge-packages', '余额充值套餐'],
  ['/system/operation-log', '操作日志'],
  ['/system/sensitive-words', '敏感词管理']
]
const adminRedirectAliases = {
  '/invitations': '/system/config',
  '/marketing/membership': '/system/config',
  '/system/email-notifications': '/system/config'
}
const adminH5Entries = adminEntries.filter(([route]) => [
  '/dashboard', '/product/list', '/product/category', '/order/list',
  '/user/list', '/player', '/marketing/coupons', '/chat/service',
  '/system/banner', '/system/content', '/system/config'
].includes(route))
const adminRoutes = surface === 'admin'
  ? adminEntries.map(([route]) => route)
  : adminH5Entries.map(([route]) => '/h5' + route)
const h5RouteMeta = {
  '/pages/index/index': { title: '首页' },
  '/pages/category/index': { title: '分类' },
  '/pages/product/list': { title: '商品列表' },
  '/pages/product/detail': { title: '服务详情' },
  '/pages/players/index': { title: '打手' },
  '/pages/players/detail': { title: '打手主页' },
  '/pages/chat/list': { title: '消息', auth: true },
  '/pages/mine/index': { title: '我的' },
  '/pages/message/index': { title: '系统通知', auth: true },
  '/pages/agreement/user': { title: '用户协议' },
  '/pages/agreement/privacy': { title: '隐私政策' },
  '/pages/agreement/about': { title: '关于我们' },
  '/pages/display/index': { title: '消费排行' },
  '/pages/membership/index': { title: '会员中心', auth: true },
  '/pages/coupons/index': { title: '优惠券', auth: true },
  '/pages/invites/index': { title: '邀请好友', auth: true },
  '/pages/order/list': { title: '我的订单', auth: true },
  '/pages/wallet/index': { title: '我的钱包', auth: true },
  '/pages/complaint/list': { title: '我的投诉工单', auth: true }
}

const h5UserRouteMeta = {
  '/pages/mine/profile': { title: '编辑资料', auth: true },
  '/pages/mine/services': { title: '设置', auth: true },
  '/pages/mine/identity': { title: '实名认证', auth: true },
  '/pages/mine/library': { title: '收藏与关注', auth: true },
  '/pages/mine/privacy-center': { title: '隐私与账号', auth: true },
  '/pages/order/list?tab=pending': { title: '我的订单', auth: true },
  '/pages/wallet/recharge': { title: '余额充值', auth: true },
  '/pages/display/index?tab=feed': { title: '消费排行' }
}
const h5UserRoutes = [...new Set([...h5PublicRoutes, ...Object.keys(h5UserRouteMeta)])]
const activeRoutes = adminMode ? adminRoutes : h5UserMode ? h5UserRoutes : h5PublicRoutes
const activeRouteMeta = adminMode
  ? Object.fromEntries((surface === 'admin' ? adminEntries : adminH5Entries).map(([route, title]) => [
      surface === 'admin' ? route : '/h5' + route,
      { title, auth: true }
    ]))
  : h5UserMode ? { ...h5RouteMeta, ...h5UserRouteMeta } : h5RouteMeta
const routeFilters = (process.env.AUDIT_ROUTE || '').split(',').map(value => value.trim()).filter(Boolean)
const routes = routeFilters.length ? activeRoutes.filter(route => routeFilters.some(filter => route.includes(filter))) : activeRoutes
const auditTheme = process.env.AUDIT_THEME || ''

const delay = ms => new Promise(resolve => setTimeout(resolve, ms))

async function waitJson(url, timeout = 15000) {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    try {
      const response = await fetch(url)
      if (response.ok) return response.json()
    } catch (_) {}
    await delay(200)
  }
  throw new Error('Browser debugging endpoint did not start: ' + url)
}

class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    this.listeners = new Map()
    ws.addEventListener('message', event => {
      const message = JSON.parse(event.data)
      if (message.id) {
        const pending = this.pending.get(message.id)
        if (!pending) return
        this.pending.delete(message.id)
        if (message.error) pending.reject(new Error(message.error.message))
        else pending.resolve(message.result)
        return
      }
      const callbacks = this.listeners.get(message.method) || []
      callbacks.forEach(callback => callback(message.params))
    })
  }

  send(method, params = {}) {
    const id = ++this.id
    this.ws.send(JSON.stringify({ id, method, params }))
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }))
  }

  on(method, callback) {
    const callbacks = this.listeners.get(method) || []
    callbacks.push(callback)
    this.listeners.set(method, callbacks)
  }

  waitFor(method, timeout = 15000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timed out waiting for ' + method)), timeout)
      const callback = params => {
        clearTimeout(timer)
        const callbacks = this.listeners.get(method) || []
        this.listeners.set(method, callbacks.filter(item => item !== callback))
        resolve(params)
      }
      this.on(method, callback)
    })
  }
}

const auditExpression = [
  '(() => {',
  '  const root = document.documentElement;',
  '  const visible = el => {',
  '    const style = getComputedStyle(el);',
  '    const rect = el.getBoundingClientRect();',
  '    return style.display !== "none" && style.visibility !== "hidden" && rect.width > 0 && rect.height > 0;',
  '  };',
  '  const rect = el => { const r = el.getBoundingClientRect(); return { width: r.width, height: r.height, left: r.left, right: r.right, top: r.top, bottom: r.bottom }; };',
  '  const titles = [...document.querySelectorAll(".screen-title")].filter(visible).map(el => ({ text: el.textContent.trim(), fontSize: parseFloat(getComputedStyle(el).fontSize), rect: rect(el) }));',
  '  const iconButtons = [...document.querySelectorAll(".screen-icon-btn")].filter(visible).map(el => ({ width: rect(el).width, height: rect(el).height, image: el.querySelector("image,img") ? rect(el.querySelector("image,img")) : null }));',
  '  const brokenImages = [...document.images].filter(img => visible(img) && img.complete && img.naturalWidth === 0).map(img => img.currentSrc || img.src);',
  '  const slotSelectors = [".product-card .cover", ".banner-img", ".category-icon"];',
  '  const slots = [];',
  '  for (const selector of slotSelectors) {',
  '    for (const el of [...document.querySelectorAll(selector)].filter(visible).slice(0, 12)) {',
  '      const r = rect(el);',
  '      slots.push({ selector, width: Math.round(r.width), height: Math.round(r.height), ratio: r.height ? Math.round(r.width / r.height * 100) / 100 : 0 });',
  '    }',
  '  }',
  '  const buttons = [...document.querySelectorAll("button, .buy-btn, .send-btn, .action-button, .primary-button")].filter(visible);',
  '  const overflowingActions = buttons.filter(el => { const r = rect(el); return r.left < -2 || r.right > innerWidth + 2; }).map(el => ({ text: (el.textContent || "").trim().slice(0, 40), rect: rect(el) }));',
  '  const text = (document.body.innerText || "").replace(/\s+/g, " ").trim();',
  '  return {',
  '    title: document.title,',
  '    path: location.href,',
  '    horizontalOverflow: Math.max(0, root.scrollWidth - root.clientWidth),',
  '    titles, iconButtons, brokenImages, slots, overflowingActions,',
  '    hasBodyText: text.length > 0, textSample: text.slice(0, 160)',
  '  };',
  '})()'
].join('\n')

async function main() {
  await mkdir(auditOutputDir, { recursive: true })
  const port = 9400 + Math.floor(Math.random() * 300)
  const profile = path.join('D:\\CodexData\\temp', 'kk520-browser-audit-' + process.pid)
  const browser = spawn(chromePath, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--remote-debugging-port=' + port,
    '--user-data-dir=' + profile,
    'about:blank'
  ], { stdio: 'ignore', windowsHide: true })

  const version = await waitJson('http://127.0.0.1:' + port + '/json/version')
  const page = await fetch('http://127.0.0.1:' + port + '/json/new?about:blank', { method: 'PUT' }).then(response => response.json())
  const ws = new WebSocket(page.webSocketDebuggerUrl || version.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', reject, { once: true })
  })

  const cdp = new Cdp(ws)
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Network.enable')
  await cdp.send('Log.enable')
  await cdp.send('Emulation.setDeviceMetricsOverride', auditViewport)

  if (adminMode) {
    const auth = JSON.parse(await readFile(adminTokenFile, 'utf8'))
    const loginLoaded = cdp.waitFor('Page.loadEventFired', 20000).catch(() => null)
    await cdp.send('Page.navigate', { url: auditBase + '/login' })
    await loginLoaded
    await delay(700)
    const expression = [
      '(() => {',
      '  const token = ' + JSON.stringify(auth.token) + ';',
      '  const info = ' + JSON.stringify({
        adminId: auth.adminId,
        nickname: auth.nickname,
        avatar: '',
        role: String(auth.role || '').toUpperCase() === 'CS' ? 'cs' : 'admin'
      }) + ';',
      '  const instance = globalThis.__CANGSHUO_INSTANCE__ || {};',
      '  localStorage.setItem(instance.adminTokenKey || "delta_admin_token", token);',
      '  localStorage.setItem(instance.adminUserKey || "delta_admin_user", JSON.stringify(info));',
      '  return true;',
      '})()'
    ].join('\n')
    await cdp.send('Runtime.evaluate', { expression, returnByValue: true })
  }

  if (h5UserMode) {
    const auth = JSON.parse(await readFile(userTokenFile, 'utf8'))
    const loaded = cdp.waitFor('Page.loadEventFired', 20000).catch(() => null)
    await cdp.send('Page.navigate', { url: auditBase + '/' })
    await loaded
    await delay(700)
    const expression = [
      '(() => {',
      '  const token = ' + JSON.stringify(auth.token) + ';',
      '  const instance = globalThis.__CANGSHUO_INSTANCE__ || {};',
      '  const prefix = instance.storagePrefix || "";',
      '  localStorage.setItem(prefix + "user_token", token);',
      '  localStorage.setItem(prefix + "app_role", "user");',
      '  return true;',
      '})()'
    ].join('\n')
    await cdp.send('Runtime.evaluate', { expression, returnByValue: true })
  }

  let activeCapture = { errors: [], badResponses: [] }
  cdp.on('Runtime.exceptionThrown', event => activeCapture.errors.push(event.exceptionDetails?.text || 'Runtime exception'))
  cdp.on('Log.entryAdded', event => {
    if (event.entry?.level === 'error') activeCapture.errors.push(event.entry.text)
  })
  cdp.on('Runtime.consoleAPICalled', event => {
    if (event.type === 'error') activeCapture.errors.push(event.args?.map(arg => arg.value || arg.description).join(' '))
  })
  cdp.on('Network.responseReceived', event => {
    if (event.response?.status >= 400) {
      activeCapture.badResponses.push({ status: event.response.status, url: event.response.url })
    }
  })

  const report = []
  for (let index = 0; index < routes.length; index++) {
    const route = routes[index]
    const routePath = '/' + route.split('?')[0].replace(/^\//, '')
    const meta = activeRouteMeta[routePath] || {}
    activeCapture = { errors: [], badResponses: [] }

    const cleanRoute = route.startsWith('/') ? route.slice(1) : route
    const url = adminMode
      ? auditBase + route + (route.includes('?') ? '&' : '?') + 'audit=' + Date.now() + index
      : auditBase + '/?audit=' + Date.now() + index + '#/' + cleanRoute
    const loaded = cdp.waitFor('Page.loadEventFired', 20000).catch(() => null)
    await cdp.send('Page.navigate', { url })
    await loaded
    let stable = 0
    for (let waitIndex = 0; waitIndex < 36; waitIndex++) {
      await delay(250)
      try {
        const stateResult = await cdp.send('Runtime.evaluate', {
          expression: [
            '(() => {',
            '  const text = document.body.innerText || "";',
            '  const loadingNode = [...document.querySelectorAll(".loading-dot, .skeleton, .page-state, .uni-loading, .el-loading-mask")].some(el => {',
            '    const style = getComputedStyle(el); const rect = el.getBoundingClientRect();',
            '    return style.display !== "none" && rect.width > 0 && rect.height > 0;',
            '  });',
            '  return { hash: location.hash, pathname: location.pathname, search: location.search, title: document.title, loading: loadingNode || text.includes("正在加载，请稍候"), textLength: text.trim().length };',
            '})()'
          ].join('\n'),
          returnByValue: true
        })
        const state = stateResult.result?.value || {}
        if (adminMode && state.pathname && !state.pathname.includes(routePath)) break
        if (!adminMode && state.hash && !state.hash.includes(routePath)) break
        if (state.loading || Number(state.textLength || 0) < (adminMode ? 20 : 5)) stable = 0
        else stable++
        if (stable >= 3) break
      } catch (_) {
        break
      }
    }
    await delay(adminMode ? 2500 : 900)
    if (auditTheme) {
      await cdp.send('Runtime.evaluate', {
        expression: "document.documentElement.setAttribute('data-theme', " + JSON.stringify(auditTheme) + ")",
        returnByValue: true
      })
      await delay(400)
    }

    let audit = {}
    try {
      const evaluated = await cdp.send('Runtime.evaluate', { expression: auditExpression, returnByValue: true })
      audit = evaluated.result?.value || {}
    } catch (error) {
      activeCapture.errors.push(error.message)
    }

    const screenshot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
    const name = String(index + 1).padStart(2, '0') + '-' + route.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') + '.png'
    await writeFile(path.join(auditOutputDir, name), Buffer.from(screenshot.data, 'base64'))

    const actualHash = audit.path ? String(audit.path).split('#')[1] || '' : ''
    let actualLocation = actualHash
    if (adminMode && audit.path) {
      try {
        const parsed = new URL(String(audit.path))
        actualLocation = parsed.pathname + parsed.search
      } catch (_) {}
    }
    const pageTitle = String(audit.title || '').trim()
    const expectedRedirect = adminMode && adminRedirectAliases[routePath] && actualLocation.startsWith(adminRedirectAliases[routePath])
    const redirectedToLogin = !actualLocation.includes(routePath) && pageTitle === '登录'
    const permissionDenied = adminMode && (actualLocation.endsWith('/403') || pageTitle.includes('无权限'))
    const skipped = adminMode
      ? permissionDenied
      : Boolean(surface === 'h5' && meta.auth && redirectedToLogin)
    const titleMismatch = !skipped && !adminMode && meta.title && pageTitle !== meta.title
    audit.textLength = String(audit.textSample || '').length
    if (adminMode || h5UserMode) delete audit.textSample
    const titleIssues = (audit.titles || []).filter(item => item.fontSize > 22)
    const iconIssues = (audit.iconButtons || []).filter(item => Math.abs(item.width - item.height) > 1)
    const productSlots = (audit.slots || []).filter(item => item.selector === '.product-card .cover' && Math.abs(item.ratio - 1.5) > 0.12)
    const bannerSlots = (audit.slots || []).filter(item => item.selector === '.banner-img' && Math.abs(item.ratio - 1.5) > 0.2)
    const categorySlots = (audit.slots || []).filter(item => item.selector === '.category-icon' && Math.abs(item.ratio - 1) > 0.12)
    const issues = []
    if (!skipped && !expectedRedirect && !actualLocation.includes(routePath)) issues.push('route was unexpectedly redirected')
    if (titleMismatch) issues.push('unexpected page title: ' + pageTitle)
    if (audit.horizontalOverflow > 2) issues.push('horizontal overflow ' + audit.horizontalOverflow + 'px')
    if ((audit.brokenImages || []).length) issues.push('broken images: ' + audit.brokenImages.length)
    if (titleIssues.length) issues.push('screen title exceeds 22px')
    if (iconIssues.length) issues.push('header icon button is not square')
    if (productSlots.length) issues.push('product slot is not 3:2')
    if (bannerSlots.length) issues.push('banner slot is not 3:2')
    if (categorySlots.length) issues.push('category icon slot is not 1:1')
    if ((audit.overflowingActions || []).length) issues.push('action overflows viewport')
    if (!audit.hasBodyText) issues.push('empty page body')
    if (adminMode && !skipped && Number(audit.textLength || 0) < 30) issues.push('page body is effectively empty')
    if (h5UserMode && !skipped && Number(audit.textLength || 0) < 8) issues.push('page body is effectively empty')
    if (!skipped && !adminMode && /正在加载|加载中/.test(audit.textSample || '')) issues.push('page remained in loading state')
    const allErrors = activeCapture.errors.filter(Boolean)
    const badResponses = activeCapture.badResponses
    const telemetryFailed = badResponses.some(item => item.url.includes('rumtc.myalicdn.com'))
    const ignoredErrors = allErrors.filter(message => {
      if (message === 'Object') return true
      if (message.includes('rumtc.myalicdn.com')) return true
      if (telemetryFailed && (message.includes('net::ERR_FAILED') || message.includes('Failed to fetch'))) return true
      if (message.includes('net::ERR_NETWORK_CHANGED')) return true
      if (!requireAuthenticated && (message.includes('401 (Unauthorized)') || message.includes('403 (Forbidden)'))) return true
      return false
    })
    const errors = allErrors.filter(message => !ignoredErrors.includes(message))
    if (errors.length) issues.push('console errors: ' + errors.length)
    const urgentResponses = badResponses.filter(item => item.status >= 500 || (item.status === 404 && !item.url.includes('/api/')))
    if (urgentResponses.length) issues.push('failed resources: ' + urgentResponses.length)

    report.push({ route, url, screenshot: name, skipped, skipReason: skipped ? (adminMode ? 'permission or feature not enabled' : 'authentication required') : '', audit, errors, ignoredErrors, badResponses, urgentResponses, issues })
    process.stdout.write(route + ': ' + (skipped ? 'SKIP (login required)' : issues.length ? 'FAIL ' + issues.join('; ') : 'OK') + '\n')
  }

  await writeFile(path.join(auditOutputDir, 'report.json'), JSON.stringify({ base: auditBase, surface, viewport: auditViewport, generatedAt: new Date().toISOString(), report }, null, 2))
  ws.close()
  browser.kill()
  const failed = report.filter(item => item.issues.length)
  process.stdout.write('Report: ' + path.join(auditOutputDir, 'report.json') + '\n')
  const skipped = report.filter(item => item.skipped)
  process.stdout.write('Pages: ' + report.length + ', failed: ' + failed.length + ', skipped: ' + skipped.length + '\n')
  process.exit(failed.length ? 1 : 0)
}

main().catch(error => {
  process.stderr.write((error.stack || error.message) + '\n')
  process.exit(2)
})




