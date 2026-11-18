import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdir, readFile } from 'node:fs/promises'
import { resolve, sep, extname } from 'node:path'
import { once } from 'node:events'
import { pathToFileURL } from 'node:url'

const playwright = process.env.PLAYWRIGHT_MODULE
  ? await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href) : await import('playwright')
const root = resolve(process.env.AUTH_UI_BUILD_DIR || 'delta-mp/dist/build/h5')
const image = 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%22120%22%20height=%2240%22%3E%3Ctext%20x=%2220%22%20y=%2224%22%3E123456%3C/text%3E%3C/svg%3E'
let policy = { primaryMethod:'WECHAT', wechatEnabled:true, passwordEnabled:true, phoneEnabled:true }
let sms = 0, logins = 0, merge = 0
let takeover = false, takeoverCount = 0, takeoverVerified = false
const mime = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.png':'image/png' }
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1')
    const p = url.pathname
    if (p === '/mock-wechat') return res.writeHead(302, {Location:'/#/pages/mine/security?wechat_ticket='+'c'.repeat(32)}).end()
    if (p.startsWith('/api/')) {
      let body = ''
      for await (const chunk of req) body += chunk
      const data = JSON.parse(body || '{}')
      const responses = {
        '/api/user/auth/login-policy': () => policy,
        '/api/user/auth/captcha': () => ({captchaId:'test-captcha', image}),
        '/api/user/auth/sms-code': () => { assert.equal(data.captchaCode,'123456'); sms++; return {requestId:'test-otp',retryAfter:60} },
        '/api/user/auth/h5-login': () => { assert.equal(data.code,'123456'); logins++; return {token:'phone-token',userId:12,phoneBound:true} },
        '/api/user/auth/wechat/exchange': () => ({bindTicket:'a'.repeat(32),phoneBound:false}),
        '/api/user/auth/wechat/security': () => {
          assert.match(req.headers.authorization, /^Bearer (phone-token|takeover-token)$/)
          return {phone:takeover ? '138****0001' : '',phoneBound:takeover,wechatBound:!takeover || takeoverCount > 0,wechatAvailable:true,passwordSet:false}
        },
        '/api/user/auth/wechat/bind/start': () => {
          assert.equal(req.headers.authorization, 'Bearer phone-token')
          return {authorizeUrl:origin+'/mock-wechat'}
        },
        '/api/user/auth/wechat/bind/complete': () => {
          assert.equal(req.headers.authorization, 'Bearer phone-token')
          assert.equal(data.ticket,'c'.repeat(32))
          return {bindTicket:'d'.repeat(32), maskedPhone:takeover ? '138****0001' : ''}
        },
        '/api/user/auth/wechat/phone/code': () => {
          assert.equal(data.captchaCode,'123456')
          if (data.ticket === 'd'.repeat(32)) {
            assert.equal(req.headers.authorization, 'Bearer phone-token')
            assert.equal(data.phone, '13800000001')
          } else assert.equal(data.ticket,'a'.repeat(32))
          return {requestId:'bind-otp',retryAfter:60}
        },
        '/api/user/auth/wechat/phone/complete': () => {
          if (data.ticket === 'd'.repeat(32)) {
            assert.equal(req.headers.authorization, 'Bearer phone-token')
            assert.equal(data.requestId, 'bind-otp')
            assert.equal(data.code, '123456')
            takeoverVerified = true
            return {confirmationTicket:'e'.repeat(32)}
          }
          return {confirmationTicket:'b'.repeat(32),phoneBound:false}
        },
        '/api/user/auth/wechat/phone/confirm': () => {
          if (data.ticket === 'e'.repeat(32)) {
            assert.equal(req.headers.authorization, 'Bearer phone-token')
            assert.equal(takeoverVerified, true)
            takeoverCount++
            return {token:'takeover-token',userId:12,phoneBound:true}
          }
          assert.equal(data.ticket,'b'.repeat(32))
          merge++
          return {token:'merged-token',userId:12,phoneBound:true}
        },
        '/api/system/config/site': () => ({site_name:'本地测试',guest_checkout_enabled:'false'}),
        '/api/system/config/theme': () => ({themeId:'default'})
      }
      return res.writeHead(200, { 'Content-Type':'application/json' }).end(JSON.stringify({code:200,data:responses[p]?.() ?? []}))
    }
    let path = resolve(root, p === '/' ? 'index.html' : '.' + decodeURIComponent(p))
    if (!path.startsWith(root + sep)) return res.writeHead(404).end()
    let content
    try { content = await readFile(path) }
    catch (e) { if (p === '/instance-config.js') return res.writeHead(200, {'Content-Type':'text/javascript'}).end(''); throw e }
    res.writeHead(200,{'Content-Type':mime[extname(path)] || 'application/octet-stream'}).end(content)
  } catch (error) { res.writeHead(error.code === 'ENOENT' ? 404 : 500).end() }
})
server.listen(0, '127.0.0.1')
await once(server,'listening')
const origin = 'http://127.0.0.1:' + server.address().port
const evidence = process.env.AUTH_UI_EVIDENCE_DIR
if (evidence) await mkdir(evidence, {recursive:true})
const capture = (page, name) => evidence && page.screenshot({path:resolve(evidence, name+'.png')})
const controlStyle = (page, selector) => page.locator(selector).first().evaluate(node => {
  const r = node.getBoundingClientRect(), c = getComputedStyle(node)
  return {top:r.top,height:r.height,width:r.width,bg:c.backgroundColor,border:c.borderTopWidth,radius:c.borderTopLeftRadius,color:c.color,boxShadow:c.boxShadow}
})
async function checkPhoneLayout(page, theme='light') {
  for (const name of ['手机号','图形验证码','短信验证码']) {
    const control = await controlStyle(page, 'uni-input[aria-label="'+name+'"]')
    assert.ok(control.height >= 48, 'full-height control: ' + name)
    if (await page.locator('.login-reference').count() && name !== '手机号') {
      const row = await controlStyle(page, 'uni-input[aria-label="' + name + '"] >> xpath=..')
      assert.equal(control.bg, 'rgba(0, 0, 0, 0)')
      assert.equal(control.border, '0px')
      assert.notEqual(row.bg, 'rgba(0, 0, 0, 0)')
    } else {
      assert.equal(control.border, '1px')
      assert.notEqual(control.bg, 'rgba(0, 0, 0, 0)')
    }
  }
  const buttons = await Promise.all([controlStyle(page,'.verify-side:not(.captcha-button)'),controlStyle(page,'.captcha-button'),controlStyle(page,'.auth-primary')])
  assert.notEqual(buttons[0].bg, buttons[2].bg)
  assert.notEqual(buttons[1].bg, buttons[2].bg)
  assert.ok(buttons.every(b => b.height >= (b === buttons[2] ? 48 : 44)), 'controls meet touch target size: ' + JSON.stringify(buttons))
  assert.equal(buttons[2].bg, theme==='dark' ? 'rgb(244, 244, 245)' : (!await page.locator('.login-reference').count() ? 'rgb(23, 23, 23)' : 'rgb(27, 27, 27)'))
}
async function checkFlatLayout(page) {
  const content = await controlStyle(page, '.login-reference .auth-content')
  assert.deepEqual([content.bg, content.border, content.radius, content.boxShadow], ['rgba(0, 0, 0, 0)', '0px', '0px', 'none'])
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
}
async function checkLoginLinks(page) {
  for (const selector of ['.auth-agreement .auth-link','.auth-alternatives .auth-alt']) {
    if (await page.locator(selector).count()) {
      const s=await controlStyle(page,selector)
      assert.equal(s.bg,'rgba(0, 0, 0, 0)')
      assert.equal(s.border,'0px')
    }
  }
}
const browser = await playwright.chromium.launch({headless:true, channel:'chrome'})
try {
  const page = await browser.newPage({viewport:{width:440,height:956}})
  const errors = []
  page.setDefaultTimeout(8000)
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', route => route.request().url().startsWith(origin) ? route.continue() : route.abort())
  const visible = (text, selector='.auth-content') => page.locator(selector).getByText(text, {exact:true}).waitFor()
  const login = origin+'/#/pages/login/index'
  await page.goto(login)
  await visible('微信快捷登录')
  await visible('手机号登录')
  await visible('密码登录')
  assert.equal(await page.locator('.auth-content uni-input').count(),0)
  assert.equal(await page.locator('.auth-content .logo').count(),0)
  await checkFlatLayout(page)
  const content = await controlStyle(page, '.auth-content')
  assert.ok(content.top + content.height < 956 && content.width <= 440)
  const icon = page.locator('.login-wechat-action svg.login-wechat-icon')
  await icon.waitFor()
  assert.equal(await icon.locator('path').count(), 2)
  const symbol = await icon.evaluate(node => {
    const box = node.getBoundingClientRect(), style = getComputedStyle(node)
    return { width:box.width, height:box.height, fill:style.fill, color:style.color }
  })
  assert.deepEqual([symbol.width,symbol.height],[24,24])
  assert.equal(symbol.fill,symbol.color,'symbol follows the button foreground')
  const modes = await controlStyle(page, '.auth-alternatives')
  const agreement = await controlStyle(page, '.auth-agreement')
  assert.ok(agreement.top > modes.top)
  await checkLoginLinks(page)
  await capture(page,'wechat-440')
  await page.locator('.login-wechat-action').click()
  await visible('请先同意用户协议和隐私政策','body')
  await page.getByText('请先同意用户协议和隐私政策',{exact:true}).waitFor({state:'hidden'})
  await page.locator('.auth-alternatives').getByText('密码登录',{exact:true}).click()
  await visible('忘记密码？')
  await checkFlatLayout(page)
  const pass=await controlStyle(page,'uni-input[aria-label="登录密码"]')
  const captcha=await controlStyle(page,'uni-input[aria-label="图形验证码"]')
  assert.ok(pass.height>=48 && captcha.top>pass.top,'password field appears before the captcha')
  await page.locator('uni-input[aria-label="登录密码"] input').fill('example123')
  await page.locator('.login-field-action').click()
  assert.equal(await page.locator('uni-input[aria-label="登录密码"] input').getAttribute('type'),'text')
  await page.locator('.login-field-action').click()
  assert.equal(await page.locator('uni-input[aria-label="登录密码"] input').getAttribute('type'),'password')
  await page.locator('uni-input[aria-label="登录密码"] input').fill('')
  await page.locator('uni-input[aria-label="登录密码"] input').blur()
  await checkLoginLinks(page)
  await capture(page,'password-440')
  await page.locator('.login-forgot').getByText('忘记密码？',{exact:true}).click()
  await visible('重置密码','.auth-heading')
  await page.locator('uni-input[aria-label="确认密码"] input').waitFor()
  await checkPhoneLayout(page)
  await checkFlatLayout(page)
  await capture(page,'reset-440')
  await page.setViewportSize({width:320,height:690})
  policy = {primaryMethod:'PHONE', wechatEnabled:false, phoneEnabled:true,passwordEnabled:false,reason:'微信登录暂不可用，已切换到手机号登录'}
  await page.reload()
  await page.locator('uni-input[aria-label="手机号"] input').waitFor()
  await page.locator('.captcha-button uni-image').waitFor()
  assert.equal(await page.locator('.auth-alternatives .auth-alt').count(),0)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true)
  await checkPhoneLayout(page)
  await checkLoginLinks(page)
  const field=page.locator('uni-input[aria-label="手机号"] input')
  await field.focus()
  assert.notEqual((await controlStyle(page,'uni-input[aria-label="手机号"]')).boxShadow,'none')
  await field.blur()
  const fallbackNotice = page.getByText('微信登录暂不可用，已切换到手机号登录',{exact:true})
  await fallbackNotice.waitFor()
  await fallbackNotice.waitFor({state:'hidden'})
  await capture(page,'phone-320')
  await page.setViewportSize({width:440,height:956})
  policy={primaryMethod:'PHONE',wechatEnabled:true,phoneEnabled:true,passwordEnabled:true}
  await page.reload()
  await visible('手机号登录 / 注册','.auth-heading')
  await visible('微信登录')
  await page.locator('.captcha-button uni-image').waitFor()
  await page.evaluate(() => document.documentElement.dataset.theme='dark')
  await page.waitForFunction(() => getComputedStyle(document.querySelector('.auth-primary')).backgroundColor === 'rgb(244, 244, 245)')
  await checkPhoneLayout(page,'dark')
  await checkFlatLayout(page)
  await checkLoginLinks(page)
  await capture(page,'phone-dark-440')
  await page.evaluate(() => document.documentElement.removeAttribute('data-theme'))
  await page.waitForFunction(() => getComputedStyle(document.querySelector('.auth-primary')).backgroundColor === 'rgb(27, 27, 27)')
  await capture(page,'phone-440')
  await page.setViewportSize({width:1280,height:900})
  await page.waitForFunction(() => document.querySelector('.auth-content').getBoundingClientRect().width > 420)
  await capture(page,'phone-desktop')
  await page.setViewportSize({width:440,height:956})
  await page.locator('uni-input[aria-label="手机号"] input').fill('13800000001')
  await page.locator('uni-input[aria-label="图形验证码"] input').fill('123456')
  await visible('获取验证码')
  await page.getByText('获取验证码',{exact:true}).click()
  await visible('验证码已发送','body')
  await page.locator('uni-input[aria-label="短信验证码"] input').fill('123456')
  await page.locator('uni-checkbox').click()
  await page.locator('.auth-primary').click()
  await page.waitForURL(url => !url.hash.includes('/pages/login/'))
  assert.deepEqual([sms,logins],[1,1])
  assert.equal(await page.getByText('设置密码',{exact:true}).count(), 0)
  console.log('PASS WeChat / phone / password / reset panels at 320px; consent and immediate OTP login')

  const bind = await browser.newPage({viewport:{width:390,height:844}})
  bind.setDefaultTimeout(8000)
  bind.on('pageerror', error => errors.push(error.message))
  await bind.route('**/*', route => route.request().url().startsWith(origin) ? route.continue() : route.abort())
  await bind.goto(login+'?wechat_ticket='+'0'.repeat(32))
  await bind.getByText('绑定手机号',{exact:true}).waitFor()
  await bind.reload()
  await bind.locator('uni-input[aria-label="手机号"] input').waitFor()
  await bind.goto(login+'?panel=reset')
  await bind.waitForURL(url => url.hash.includes('/pages/login/bind-phone'))
  await bind.locator('uni-input[aria-label="手机号"] input').waitFor()
  assert.equal(await bind.getByText('稍后绑定').count(),0)
  await bind.getByText('取消，选择其他登录方式',{exact:true}).click()
  await bind.getByText('手机号登录',{exact:true}).waitFor()
  assert.equal(await bind.evaluate(() => sessionStorage.getItem('delta.wechat-phone-binding')), null)
  await bind.goto(login+'?wechat_ticket='+'0'.repeat(32))
  await bind.getByText('绑定手机号',{exact:true}).waitFor()
  assert.equal(await bind.evaluate(() => localStorage.getItem('user_token')),null)
  await bind.locator('.captcha-button uni-image').waitFor()
  await checkPhoneLayout(bind)
  await capture(bind,'bind-390')
  await bind.locator('uni-input[aria-label="手机号"] input').fill('13800000001')
  await bind.locator('uni-input[aria-label="图形验证码"] input').fill('123456')
  await bind.getByText('获取验证码',{exact:true}).click()
  await bind.getByText('验证码已发送',{exact:true}).waitFor()
  await bind.locator('uni-input[aria-label="短信验证码"] input').fill('123456')
  await bind.getByText('确认绑定',{exact:true}).click()
  await bind.getByText('确认合并',{exact:true}).waitFor()
  assert.equal(merge,0)
  await bind.getByText('确认合并',{exact:true}).click()
  await bind.waitForURL(url => !url.hash.includes('/pages/login/'))
  assert.equal(merge,1)
  assert.deepEqual(errors,[])
  console.log('PASS OAuth ticket, reload, no formal token before binding, explicit confirmation at 390px')

  policy = {primaryMethod:'PHONE', wechatEnabled:true, phoneEnabled:true, passwordEnabled:false}
  const security = await browser.newPage({viewport:{width:440,height:956},userAgent:'Mozilla/5.0 Mobile MicroMessenger/8.0.1'})
  security.setDefaultTimeout(8000)
  security.on('pageerror', error => errors.push(error.message))
  await security.route('**/*', route => route.request().url().startsWith(origin) ? route.continue() : route.abort())
  await security.addInitScript(() => { if (!localStorage.getItem('user_token')) localStorage.setItem('user_token', 'phone-token') })
  const accountPage = origin+'/#/pages/mine/security'
  await security.goto(accountPage)
  await security.getByText('绑定手机号',{exact:true}).waitFor()
  await capture(security,'security-440')
  await security.getByText('绑定手机号',{exact:true}).click()
  await security.getByText('取消并返回账号安全',{exact:true}).click()
  await security.getByText('绑定手机号',{exact:true}).waitFor()
  assert.equal(await security.evaluate(() => localStorage.getItem('user_token')), 'phone-token')
  takeover = true
  await security.reload()
  await security.getByText('去绑定',{exact:true}).click()
  await security.getByText('验证手机号',{exact:true}).waitFor()
  await security.locator('uni-input[aria-label="手机号"] input').fill('13800000001')
  await security.locator('uni-input[aria-label="图形验证码"] input').fill('123456')
  await security.getByText('获取验证码',{exact:true}).click()
  await security.getByText('验证码已发送',{exact:true}).waitFor()
  await security.locator('uni-input[aria-label="短信验证码"] input').fill('123456')
  await security.getByText('确认绑定',{exact:true}).click()
  await security.getByText('确认转移微信绑定',{exact:true}).waitFor()
  assert.equal(takeoverCount, 0)
  await security.getByText('确认转移微信绑定',{exact:true}).click()
  await security.getByText('已绑定',{exact:true}).waitFor()
  assert.equal(takeoverCount, 1)
  assert.deepEqual(errors,[])
  console.log('PASS account security preserves session on cancel and requires SMS plus confirmation for takeover')
} finally {
  await browser.close()
  server.close()
}
