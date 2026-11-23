// Run from delta-admin-ui. QA_PLAYWRIGHT_MODULE may point to the shared QA installation.
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'
const { chromium } = await import(process.env.QA_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('../', import.meta.url))
const groups = ['服务','订单','我的'].map((name, i) => ({ id:i+1, name, children:
  Array.from({length:5}, (_, k) => ({ id:(i+1)*10+k, name:k === 4 ? '较长的子菜单显示名称' : '子项' + (k+1), type:'click', children:[] })) }))
const server = await createServer({ root, logLevel:'error', server:{host:'127.0.0.1',port:0}, plugins:[{
  name:'menu-preview-fixture',
  resolveId(id) { if(id === '/qa-menu.js') return '\0qa-menu.js' },
  load(id) { if(id === '\0qa-menu.js') return `import {createApp,h} from 'vue';import 'element-plus/theme-chalk/index.css';
    import Preview from '/src/views/wechat/WechatMenuPhonePreview.vue';
    createApp({render:()=>h(Preview,{roots:${JSON.stringify(groups)},accountName:'预览测试'})}).mount('#app')` },
  configureServer(s) { s.middlewares.use('/qa-menu.html', (_req,res) => {
    res.setHeader('Content-Type','text/html; charset=utf-8')
    res.end('<meta name="viewport" content="width=device-width, initial-scale=1"><div id="app"></div><script type="module" src="/qa-menu.js"></script>')
  }) }
}] })
let browser
try {
  await server.listen()
  browser = await chromium.launch({ executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined })
  const page = await browser.newPage()
  await page.goto('http://127.0.0.1:' + server.httpServer.address().port + '/qa-menu.html')
  for(const width of [390,240]) {
    await page.setViewportSize({width,height:700})
    const triggers = page.getByRole('button', {expanded:false})
    await triggers.first().waitFor()
    const bar = page.locator('.bar'), phone = page.locator('.phone'), popup = page.getByRole('list')
    const base = await bar.boundingBox(), frame = await phone.boundingBox()
    for(const [index,group] of groups.entries()) {
      const trigger = page.getByRole('button',{name:group.name,exact:true})
      await trigger.click()
      const btn = await trigger.boundingBox(), box = await popup.boundingBox(), items = popup.getByRole('button')
      assert.equal(await popup.count(),1)
      assert.deepEqual(await items.allTextContents(),group.children.map(c=>c.name))
      const center=btn.x+btn.width/2
      assert.ok(box.y >= frame.y && box.y+box.height <= base.y && box.x >= frame.x && box.x+box.width <= frame.x+frame.width)
      assert.ok(center >= box.x && center <= box.x+box.width, 'points back to its own trigger')
      assert.ok(Math.abs(box.x+box.width/2-center)<2 || box.x-frame.x<9 || frame.x+frame.width-box.x-box.width<9, 'centered unless clamped to an edge')
      assert.equal((await bar.boundingBox()).y,base.y)
      let bottom=0
      for(const item of await items.all()) {const r=await item.boundingBox();assert.ok(r.y>=bottom);bottom=r.y+r.height}
      if(index===1 && width===390 && process.env.QA_MENU_SCREENSHOT) await phone.screenshot({path:process.env.QA_MENU_SCREENSHOT})
    }
    await popup.getByRole('button').last().click()
    assert.equal(await popup.count(),0)
    assert.equal(await page.getByRole('status').innerText(),'点击事件')
    await triggers.first().click(); await page.locator('.conversation').click({position:{x:3,y:3}}); assert.equal(await popup.count(),0)
    await triggers.first().click(); await page.keyboard.press('Escape'); assert.equal(await popup.count(),0)
    const first = page.getByRole('button',{name:groups[0].name,exact:true})
    await first.click(); await first.click(); assert.equal(await popup.count(),0)
    console.log('PASS: three anchors, five rows, stable footer, switching and dismissal at width',width)
  }
} finally { await browser?.close(); await server.close() }
