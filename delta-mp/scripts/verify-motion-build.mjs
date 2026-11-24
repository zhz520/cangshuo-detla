// Checks only the two locally built release outputs. No dev server, credentials or external requests.
import assert from 'node:assert/strict'
import path from 'node:path'
import { readFile, readdir } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
const root = fileURLToPath(new URL('../dist/build/', import.meta.url))
const read = p => readFile(p,'utf8')
const app = JSON.parse(await read(path.join(root,'mp-weixin/app.json')))
const routes = [...app.pages,...(app.subPackages || app.subpackages || []).flatMap(p => p.pages.map(page => p.root+'/'+page))]
const source = JSON.parse(await read(new URL('../pages.json',import.meta.url)))
assert.deepEqual(new Set(routes),new Set([...source.pages.map(p=>p.path),...source.subPackages.flatMap(p=>p.pages.map(page=>p.root+'/'+page.path))]))
for (const r of routes) await read(path.join(root,'mp-weixin',r+'.wxml'))
for (const platform of ['h5','mp-weixin']) {
  const base=path.join(root,platform)
  let headerCss=''
  for (const f of await readdir(base,{recursive:true})) {
    if (!/\.(?:js|html|json|css|wxss|wxml)$/i.test(f)) continue
    const content=await read(path.join(base,f))
    assert.doesNotMatch(content,/cloudauth|jsvm_all/i,platform+'/'+f)
    if (platform==='h5') {
      assert.doesNotMatch(content,/(?:cdn\.dcloud\.net\.cn|cdn\.dcimg\.net)\/img\/shadow-|shadow-preload/i,f)
      if(f.endsWith('.css') && content.includes('.uni-page-head-shadow-grey')) headerCss+=content
    }
    if(platform==='mp-weixin') assert.doesNotMatch(content,/data-delta-enter|delta-page-back|delta-page-in\b|delta-loading-pulse/,f)
  }
  if(platform==='h5') for(const color of ['grey','blue','green','orange','red','yellow']) {
    assert.match(headerCss,new RegExp('\\.uni-page-head-shadow-'+color+'::?after\\s*\\{[^}]*linear-gradient\\('),color)
  }
}
console.log(routes.length+' registered WXML routes; release bundles: no cloud auth script; native stack: no H5 page animation.')
if(!process.env.PLAYWRIGHT_MODULE) process.exit(0)
const engine = process.env.MOTION_BROWSER || 'chromium'
assert.ok(['chromium','webkit'].includes(engine))
const playwright = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE))
const browser = await playwright[engine].launch({executablePath:engine==='chromium' ? process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE : undefined})
const origin='http://localhost:48973'
try {
 for(const reducedMotion of ['no-preference','reduce']) {
  const preview = reducedMotion === 'no-preference' && process.env.MOTION_PREVIEW_DIR
  if (preview) assert.match(path.resolve(preview), /^D:[\\/]/i)
  const size={width:390,height:844}
  const ctx=await browser.newContext({viewport:size,reducedMotion,serviceWorkers:'block',
    ...(preview ? { recordVideo:{dir:preview,size} } : {})})
  if(process.env.MOTION_FORCE_FALLBACK==='1') await ctx.addInitScript(()=>{
    Object.defineProperty(document,'startViewTransition',{value:undefined,configurable:true})
  })
  const errors=[], external=[]
  await ctx.routeWebSocket('**/*',w=>w.close())
  await ctx.route('**/*',async r=>{
    const url=new URL(r.request().url())
    if(/cloudauth|jsvm_all|\/img\/shadow-\w+\.png/i.test(url.href)) external.push(url.origin)
    if(['xhr','fetch'].includes(r.request().resourceType())) {
      const product={id:71,name:'Synthetic fixture',price:1}
      const data=url.pathname.endsWith('/product/list') ? {records:Array.from({length:10},(_,i)=>({...product,id:71+i}))} :
        /\/product\/\d+/.test(url.pathname) ? product : []
      return r.fulfill({json:{code:200,data},headers:{'access-control-allow-origin':'*'}})
    }
    if(url.origin!==origin) return r.abort()
    const base=path.join(root,'h5'), f=path.resolve(base,'.'+decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname))
    if(!f.startsWith(base+path.sep)) return r.abort()
    try {return await r.fulfill({path:f})} catch {return r.abort()}
  })
  const page=await ctx.newPage()
  page.setDefaultTimeout(10000)
  page.on('pageerror',e=>{ errors.push(e.message); console.error('Fixture page error:',e.message) })
  page.on('console',msg=>{ if (/interactive-widget/i.test(msg.text())) errors.push(msg.text()) })
  await page.goto(origin+'/#/pages/index/index')
  const body=route=>page.locator('uni-page[data-page="'+route+'"]:visible')
  const home=body('pages/index/index'), search=body('pages/product/list')
  await home.locator('.search-bar').waitFor()
  if(reducedMotion==='no-preference') {
    const shadows=await page.evaluate(()=>{
      const root=document.documentElement, original=root.getAttribute('data-theme'), sample=document.createElement('div')
      document.body.appendChild(sample)
      const values=[]
      try {
        for(const theme of ['default','dark','neumorphism']) {
          if(theme==='default') root.removeAttribute('data-theme'); else root.setAttribute('data-theme',theme)
          for(const color of ['grey','blue','green','orange','red','yellow']){
            sample.className='uni-page-head-shadow uni-page-head-shadow-'+color
            const style=getComputedStyle(sample,'::after')
            values.push([style.backgroundImage,style.height])
          }
        }
      } finally { sample.remove(); if(original===null) root.removeAttribute('data-theme'); else root.setAttribute('data-theme',original) }
      return values
    })
    assert.equal(shadows.length,18)
    for(const [image,height] of shadows){ assert.match(image,/linear-gradient\(/); assert.equal(height,'5px') }
    // The removed framework preload used to fire three seconds after initial paint.
    await page.waitForTimeout(3300)
    assert.deepEqual(external,[])
  }
  const extensions = await page.evaluate(()=>({
    optedIn: document.querySelector('meta[name="viewport"]').content.includes('interactive-widget='),
    supported: navigator.userAgentData?.brands?.some(b=>b.brand==='Chromium' && parseInt(b.version)>=108) || false
  }))
  assert.equal(extensions.optedIn, engine==='chromium' && extensions.supported)
  const marker='[data-delta-enter]'
  if(reducedMotion==='no-preference') await home.locator(marker).waitFor()
  const homeScroll=await home.locator('.home-scroll').elementHandle()
  await home.locator('.search-bar').click()
  await search.locator('input').waitFor()
  if(reducedMotion==='no-preference'){
    const target=search.locator(marker); await target.waitFor()
    assert.equal(await target.getAttribute('data-delta-enter'),'enter')
    assert.equal(await target.evaluate(el=>getComputedStyle(el).animationName),'delta-page-in')
  }else assert.equal(await search.locator(marker).count(),0)
  await search.locator('input').fill('fixture')
  if(preview) await page.waitForTimeout(600)
  await search.locator('.product-card').nth(4).click()
  await body('pages/product/detail').waitFor()
  if(preview) await page.waitForTimeout(650)
  await page.goBack()
  await search.locator('input').waitFor()
  assert.equal(await search.locator('input').inputValue(),'fixture')
  if(reducedMotion==='no-preference') {
    await search.locator('[data-delta-enter="back"]').waitFor()
  } else assert.equal(await search.locator(marker).count(),0)
  if(preview) await page.waitForTimeout(500)
  await page.goBack()
  await home.locator('.search-bar').waitFor()
  if(reducedMotion==='no-preference') await home.locator(marker).waitFor()
  assert.equal(await homeScroll.evaluate(el=>el===document.querySelector('uni-page[data-page="pages/index/index"] .home-scroll')),true)
  const viewTransition = await page.evaluate(()=>typeof document.startViewTransition==='function')
  const tabSentinel = await page.evaluate(()=>{window.__tabSentinel=String(Date.now())+Math.random();return window.__tabSentinel})
  await page.evaluate(()=>{
    window.__tabMotion=[]
    new MutationObserver(()=>window.__tabMotion.push(document.documentElement.getAttribute('data-delta-view')))
      .observe(document.documentElement,{attributes:true,attributeFilter:['data-delta-view']})
  })
  await page.locator('.custom-tabbar .tabbar-item').nth(1).click()
  await body('pages/category/index').waitFor()
  await body('pages/category/index').locator('.category-page').waitFor()
  assert.equal(await page.evaluate(()=>window.__tabSentinel),tabSentinel,'tab switch reloaded the document')
  assert.equal(await page.evaluate(()=>performance.getEntriesByType('navigation').length),1,'tab switch added a document navigation')
  await page.locator('.custom-tabbar .tabbar-item.active').filter({hasText:'分类'}).waitFor()
  if(reducedMotion==='no-preference'){
    if(viewTransition){
      await page.waitForFunction(()=>window.__tabMotion.includes('tab'))
      await page.waitForFunction(()=>!document.documentElement.hasAttribute('data-delta-view'))
    }else{
      const category=body('pages/category/index')
      await category.locator('[data-delta-enter="tab"]').waitFor()
      assert.equal(await category.locator('[data-delta-enter="tab"]').first().evaluate(el=>getComputedStyle(el).animationName),'delta-tab-in')
    }
  }
  assert.deepEqual(errors,[]); assert.deepEqual(external,[])
  console.log('Offline '+engine+': home → search → history back, cached scroll container, tab '+(viewTransition?'view transition':'fallback')+', viewport, '+reducedMotion+' OK')
  await ctx.close()
  if(preview) console.log('H5 fixture recording:', await page.video().path())
 }
} finally { await browser.close() }
