import { createServer } from 'node:http'
import { readFile, stat, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'

// PLAYWRIGHT_MODULE can point to an existing Playwright installation.
const { chromium, webkit, devices } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright')
const root = path.resolve(fileURLToPath(new URL('../../delta-mp/dist/build/h5/', import.meta.url)))
const output = path.resolve(process.env.QA_OUTPUT || 'D:/CodexData/outputs/detla-1.4.1')
if (process.platform === 'win32') assert.match(output, /^D:[\\/]/i)
await mkdir(output, { recursive: true })
const server = createServer(async (req, res) => {
  try {
    let file = path.resolve(root, '.' + decodeURIComponent(new URL(req.url, 'http://local').pathname))
    if (!file.startsWith(root + path.sep)) file = path.join(root, 'index.html')
    try { if (!(await stat(file)).isFile()) file = path.join(root, 'index.html') } catch { file = path.join(root, 'index.html') }
    res.writeHead(200, { 'Content-Type': { '.js':'text/javascript', '.css':'text/css', '.html':'text/html', '.svg':'image/svg+xml', '.png':'image/png', '.wav':'audio/wav' }[path.extname(file)] || 'application/octet-stream' })
    res.end(await readFile(file))
  } catch { res.writeHead(404); res.end() }
})
await new Promise(r => server.listen(0, '127.0.0.1', r))
const base = 'http://127.0.0.1:' + server.address().port
const results = []
const cases = [
  { name:'iPhone', engine:webkit, options:devices['iPhone 13'] },
  { name:'Android', engine:chromium, options:devices['Pixel 7'] },
  { name:'WeChat', engine:chromium, options:{ ...devices['Pixel 7'], userAgent:devices['Pixel 7'].userAgent + ' MicroMessenger/8.0.60' } },
  { name:'QQ', engine:webkit, options:{ ...devices['iPhone 13'], userAgent:devices['iPhone 13'].userAgent + ' QQ/9.1.0' } },
  { name:'Small320', engine:webkit, options:{ ...devices['iPhone SE'], viewport:{width:320,height:568} } },
  { name:'Desktop', engine:chromium, options:{ viewport:{width:1440,height:900} } },
  { name:'ReducedMotion', engine:chromium, options:{ ...devices['Pixel 7'], reducedMotion:'reduce' } }
]
try {
  for (const item of cases) {
    const browser = await item.engine.launch({ headless:true })
    const context = await browser.newContext(item.options)
    const page = await context.newPage(), errors = [], counts = new Map()
    let failOrders = true, failWithdraw = false, withdrawalPosts = 0
    page.on('pageerror', error => errors.push(error.message))
    await context.addInitScript(() => { localStorage.setItem('player_token','test-player'); localStorage.setItem('user_token','test-user'); localStorage.setItem('app_role','player') })
    await context.route('**/api/**', async route => {
      const url = new URL(route.request().url()), p = url.pathname.replace(/^\/api/, '')
      counts.set(p, (counts.get(p) || 0) + 1)
      let data = {}, status = 200
      if (p === '/system/config/site') data = {site_name:'测试电竞',player_deposit_mode:'NONE'}
      else if (p === '/player/profile') data = {id:'1',nickname:'北泽',status:'ACTIVE',isOnline:1,levelName:'进阶接单员'}
      else if (p === '/player/home') { await new Promise(r=>setTimeout(r,450)); data = {todayCompleted:12,todayIncome:128.5,wallet:{totalIncome:3680.5,balance:500}} }
      else if (p === '/common/wechat/binding') data = {enabled:false,licensed:false}
      else if (p === '/player/work/my') {
        if (!url.searchParams.get('status') && failOrders) { failOrders = false; status = 503 }
        else data = {records:[],total:0}
      }
      else if (p === '/product/category/all') data = [{id:1,name:'三角洲',parentId:0},{id:2,name:'其他',parentId:0}]
      else if (p === '/player/order/hall') data = {records:[],total:0}
      else if (p === '/player/account') data = [{id:1,type:'ALIPAY',account:'13912345678',isDefault:true}]
      else if (p === '/player/earnings/summary') { if (failWithdraw) status=503; data={balance:500,totalIncome:3680.5} }
      else if (p === '/player/withdraw/deposit-status') data = {needsPayment:false,requiredAmount:0}
      else if (p === '/player/withdraw' && route.request().method() === 'POST') { withdrawalPosts++; await new Promise(r=>setTimeout(r,350)); data = {} }
      else if (p === '/player/withdraw' || p === '/player/earnings/detail' || p === '/player/order/invite-list') data = {records:[],total:0}
      else if (p === '/common/chat/session/list') data = []
      else if (p === '/message/unread-count') data = 0
      else if (p === '/player/remind') data = {inviteCount:0,systemUnread:2,messageUnread:0}
      await route.fulfill({ status, contentType:'application/json', body:JSON.stringify({code:status===200?200:503,data,msg:status===200?'':'测试网络错误'}) })
    })
    try {
      await page.goto(base + '/#/pages-player/mine/index')
      await page.locator('.state-skeleton').first().waitFor()
      await page.locator('.overview-name').filter({hasText:'北泽'}).waitFor()
      assert.match(await page.locator('.income-number').innerText(), /3,?680\.50/)
      assert.equal(await page.getByText('公众号消息通知',{exact:true}).count(), 0)
      assert.equal(counts.get('/player/home'), 1)
      assert.equal(counts.get('/player/profile'), 1)
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth > innerWidth + 2), false)
      await page.screenshot({path:path.join(output,`player-${item.name}.png`),fullPage:true,animations:'disabled'})
      await page.locator('.overview-shortcuts .action').nth(1).click()
      await page.getByText('暂时未能加载',{exact:true}).waitFor()
      await page.getByRole('button',{name:'重新加载',exact:true}).click()
      await page.getByText('暂无订单',{exact:true}).waitFor()
      assert.equal(counts.get('/player/work/my'), 3) // dashboard preview, failure, retry
      await page.locator('.custom-tabbar .tabbar-item').first().click()
      await page.getByText('暂无可接订单',{exact:true}).waitFor()
      await page.goto(base + '/#/pages-player/withdraw/index')
      await page.getByRole('button',{name:'确认提现',exact:true}).waitFor()
      await page.locator('.form-item').nth(1).click()
      await page.locator('.account-option').filter({hasText:'139****5678'}).click()
      await page.locator('input').first().fill('10.00')
      await page.getByRole('button',{name:'确认提现',exact:true}).dblclick({delay:25})
      await page.waitForURL('**/#/pages-player/withdraw/list')
      assert.equal(withdrawalPosts, 1)
      await page.getByText('暂无提现记录',{exact:true}).waitFor()
      failWithdraw = true
      await page.goto(base + '/#/pages-player/withdraw/index')
      await page.getByText('暂时未能加载',{exact:true}).waitFor()
      assert.equal(await page.getByRole('button',{name:'确认提现',exact:true}).count(), 0)
      failWithdraw = false
      await page.getByRole('button',{name:'重新加载',exact:true}).click()
      await page.getByRole('button',{name:'确认提现',exact:true}).waitFor()
      if (item.name === 'ReducedMotion') assert.equal(await page.locator('uni-page-body > uni-view').first().evaluate(el=>getComputedStyle(el).animationName), 'none')
      assert.deepEqual(errors, [])
      results.push({name:item.name,passed:true,checks:['skeleton','income','unlicensed hidden','deduplicated reads','no overflow','failed order retry','hall empty','masked account','single withdrawal submit','withdraw fail closed','no page errors']})
      console.log(item.name + ' passed')
    } catch (error) { await page.screenshot({path:path.join(output,`failure-${item.name}.png`),fullPage:true}); results.push({name:item.name,passed:false,error:error.message,errors}); throw error }
    finally { await context.close(); await browser.close() }
  }
} finally { await writeFile(path.join(output,'player-ui-results.json'),JSON.stringify(results,null,2)); await new Promise(r=>server.close(r)) }
