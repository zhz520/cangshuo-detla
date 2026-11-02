import { createServer } from 'node:http'
import { readFile, stat, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { chromium, devices } from 'file:///D:/codeStudy/code/cangshuo-detla-authorize/node_modules/playwright/index.mjs'
import { expect } from 'file:///D:/codeStudy/code/cangshuo-detla-authorize/node_modules/playwright/test.mjs'

const root = path.resolve('dist')
const output = path.resolve(process.env.QA_OUTPUT || 'D:/CodexData/outputs/detla-1.4.2')
await mkdir(output, { recursive: true })
const server = createServer(async (req, res) => {
  try {
    let file = path.resolve(root, '.' + decodeURIComponent(new URL(req.url, 'http://local').pathname))
    try { if (!(await stat(file)).isFile()) file = path.join(root, 'index.html') } catch { file = path.join(root, 'index.html') }
    res.writeHead(200, { 'Content-Type': { '.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png' }[path.extname(file)] || 'application/octet-stream' })
    res.end(await readFile(file))
  } catch { res.writeHead(404); res.end() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${server.address().port}`
const eventOptions = [
  { id:'ORDER_PAID',name:'订单已支付',description:'新订单支付成功后通知运营' },
  { id:'ORDER_ACCEPTED',name:'订单已接单',description:'打手接单后通知运营' },
  { id:'WITHDRAW_APPLY',name:'提现申请',description:'打手提交提现申请时通知运营' }
]
const results = []
try {
  for (const item of [{name:'Desktop', options:{viewport:{width:1440,height:900}}},{name:'Mobile', options:devices['Pixel 7']}]) {
    const browser = await chromium.launch({headless:true}), context = await browser.newContext(item.options), page = await context.newPage(), errors = []
    const writes = [], tests = [], retries = []
    page.on('pageerror', error => errors.push(error.message))
    await context.addInitScript(() => {
      localStorage.setItem('delta_admin_token','email-ui-test')
      localStorage.setItem('delta_admin_user',JSON.stringify({id:1,username:'admin',nickname:'管理员',role:'admin'}))
    })
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url()), pathname = url.pathname.replace(/^\/api/, ''), method = route.request().method()
      let data = null, status = 200
      if (pathname === '/system/config/site') data = {site_name:'见高山电竞',site_admin_title:'见高山电竞管理后台'}
      else if (pathname === '/cs/permissions') data = ['system.manage','*']
      else if (pathname === '/admin/platform/features') data = [{id:'email_notifications',name:'邮件通知',enabled:true,entitled:true,expiresAt:'2027-11-01T15:59:59Z'}]
      else if (pathname === '/admin/email-notifications/settings' && method === 'GET') data = {licensed:true,enabled:true,host:'smtp.example.com',port:465,security:'TLS',username:'sender@example.com',fromAddress:'sender@example.com',senderName:'见高山电竞',recipients:['ops-a@example.com'],events:['ORDER_PAID','ORDER_ACCEPTED'],passwordConfigured:true,availableEvents:eventOptions}
      else if (pathname === '/admin/email-notifications/settings' && method === 'PUT') { const body=route.request().postDataJSON();writes.push(body);data={...body,passwordConfigured:true,availableEvents:eventOptions} }
      else if (pathname === '/admin/email-notifications/records' && method === 'GET') data = [{id:'record-1',recipient:'ops-a@example.com',subject:'新订单已支付',event_type:'ORDER_PAID',status:'RETRY',attempts:1,last_error:'SMTP_TEMPORARY',created_at:'2026-11-02 18:20:00'}]
      else if (pathname === '/admin/email-notifications/test') {tests.push(route.request().postDataJSON());data={queued:true}}
      else if (/^\/admin\/email-notifications\/records\/.+\/retry$/.test(pathname)) {retries.push(pathname.split('/')[4]);data={queued:true}}
      else if (pathname === '/admin/platform/overview') data={customerId:'742',instanceId:'test',version:'1.4.2',channel:'stable',licenseStatus:'VALID',allowedDomains:['admin.example.com']}
      else data = []
      await route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:200,data,msg:''})})
    })
    try {
      await page.goto(base + '/system/email-notifications', {waitUntil:'networkidle'})
      await page.getByRole('heading',{name:'邮件通知',exact:true}).waitFor()
      const recipients=page.locator('.recipient-editor')
      await expect(recipients.locator('.el-tag')).toHaveCount(1)
      const input=page.getByPlaceholder('输入、粘贴或换行添加多个邮箱')
      await input.fill('ops-b@example.com, OPS-A@example.com; ops-c@example.com')
      await page.getByRole('button',{name:'添加',exact:true}).click()
      await expect(recipients.locator('.el-tag')).toHaveCount(3)
      await page.locator('.event-grid .el-checkbox').nth(1).click()
      await page.getByRole('button',{name:'保存配置',exact:true}).click()
      await page.waitForTimeout(150)
      if (!writes.length) throw Error('save did not submit')
      if (!writes[0].recipients.includes('ops-c@example.com') || writes[0].recipients.filter(value=>value.toLowerCase()==='ops-a@example.com').length!==1) throw Error('recipient dedupe failed')
      if (!writes[0].events.includes('ORDER_PAID') || writes[0].events.includes('ORDER_ACCEPTED')) throw Error('event selection failed')
      await page.getByRole('button',{name:'发送测试邮件',exact:true}).click()
      const prompt=page.locator('.el-message-box input')
      await prompt.fill('test@example.com')
      await page.locator('.el-message-box').getByRole('button',{name:'确定',exact:true}).click()
      await page.waitForTimeout(150)
      if (tests.length!==1) throw Error('test email not queued')
      const row=page.locator('.el-table__body tr').filter({hasText:'ops-a@example.com'})
      await row.getByRole('button',{name:'重试',exact:true}).click()
      await page.waitForTimeout(150)
      if (retries.length!==1) throw Error('retry did not execute')
      const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2)
      if (overflow) throw Error('horizontal overflow')
      if (errors.length) throw Error(errors.join('\n'))
      await page.screenshot({path:path.join(output,`email-notifications-${item.name}.png`),fullPage:true,animations:'disabled'})
      results.push({name:item.name,passed:true,recipients:3,eventsSaved:1,testMail:true,retry:true})
    } finally { await context.close(); await browser.close() }
  }
  await writeFile(path.join(output,'email-notifications-browser.json'),JSON.stringify(results,null,2))
  console.log(JSON.stringify(results))
} finally { await new Promise(resolve=>server.close(resolve)) }
