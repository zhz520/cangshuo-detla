import assert from 'node:assert/strict'
import { origin, session, ok } from './admin-routes-browser-fixture.mjs'

// These cases exercise real controls and history on the built SPA. Only the
// network is synthetic. In particular, no access to Vue internals/router.push.
export async function runJourneys(browser) {
  const failed = []
  let passed = 0
  for (const h5 of [false, true]) {
    const prefix = h5 ? '/h5' : '', at = p => origin + prefix + p
    async function visit(label, options, body) {
      if (process.env.ROUTE_JOURNEY && !label.includes(process.env.ROUTE_JOURNEY)) return
      const { ctx, page, issues } = await session(browser, { h5, ...options })
      try {
        await body(page)
        assert.deepEqual([...issues], [])
        passed++; console.log('PASS journey:', h5 ? 'H5' : 'PC', label)
      } catch (e) {
        const diagnosis = [e.message.slice(0, 1200), ...issues].join(' | ')
        failed.push(label); console.error('FAIL journey:', h5 ? 'H5' : 'PC', label, diagnosis)
      } finally { await ctx.close() }
    }

    let submits = 0
    await visit('anonymous → login validation → authenticated return to bookmarked query', {
      auth: 'anonymous', theme: 'dark', reduced: true,
      override: ({ pathname, method, req }) => {
        if (method === 'POST' && pathname === '/cs/auth/login') {
          assert.deepEqual(req.postDataJSON(), { username: 'route-qa', password: 'fixture-only', captchaId: 'SYNTHETIC', captchaCode: '123456' })
          submits++
          return ok({ token: 'fixture-admin', adminId: 900001, nickname: 'QA', role: 'ADMIN' })
        }
      }
    }, async page => {
      const bookmark = at('/product/list?qa=return')
      await page.goto(bookmark)
      const username = page.getByPlaceholder('用户名')
      await username.waitFor()
      assert.equal(new URL(page.url()).pathname, prefix + '/login')
      assert.equal(new URL(page.url()).searchParams.get('redirect'), prefix + '/product/list?qa=return')
      const submit = page.getByRole('button', { name: /登\s*录/ })
      await submit.click()
      await page.getByText('请输入用户名', { exact: true }).waitFor()
      assert.equal(submits, 0)
      await username.fill('route-qa'); await page.getByPlaceholder('密码', { exact: true }).fill('fixture-only')
      await page.getByPlaceholder('图形验证码').fill('123456')
      await page.evaluate(() => {
        window.__loginOwners = []
        new MutationObserver(() => {
          const shell = document.querySelector('.page-viewport--shell.viewport-enter-active')
          if (shell) window.__loginOwners.push({kind:shell.dataset.navKind, nested:Boolean(shell.querySelector('.viewport-enter-active'))})
        }).observe(document.getElementById('app'),{subtree:true,childList:true,attributes:true,attributeFilter:['class']})
      })
      await submit.click(); await page.waitForURL(bookmark)
      await page.waitForFunction(() => window.__loginOwners.some(s=>s.kind==='shell'))
      assert.ok(await page.evaluate(() => window.__loginOwners.every(s=>!s.nested)), 'login handoff animates only the outer outlet')
      await page.getByRole('button', { name: '新增商品' }).waitFor()
      assert.equal(submits, 1)
    })

    await visit('product list → create → cancel/back → order menu twice → browser back/forward', {}, async page => {
      await page.goto(at('/product/list'))
      const add = page.getByRole('button', { name: '新增商品' })
      await add.click(); await page.waitForURL(at('/product/create'))
      await page.getByPlaceholder('请输入商品名称').fill('未提交的合成草稿')
      await page.getByRole('button', { name: '取消', exact: true }).click()
      await page.waitForURL(at('/product/list')); await add.waitFor()
      const menu = h5 ? page.getByRole('navigation', { name: 'H5 快捷导航' }) : page.getByRole('navigation', { name: '主导航' })
      if (!h5) await menu.getByText('订单与售后', { exact: true }).click()
      const orders = h5 ? menu.getByRole('button', { name: '订单', exact: true }) : menu.getByText('订单列表', { exact: true })
      await page.evaluate(()=>{
        const root=document.querySelector('.layout-main')
        const observer=new MutationObserver(()=>{
          const frame=root.querySelector('.viewport-enter-active[data-nav-kind="tab"]')
          if(!frame)return
          const css=getComputedStyle(frame)
          window.__menuMotion={duration:parseFloat(css.transitionDuration),ease:css.transitionTimingFunction}
          observer.disconnect()
        })
        observer.observe(root,{subtree:true,attributes:true,childList:true,attributeFilter:['class']})
      })
      await orders.click(); await page.waitForURL(at('/order/list'))
      await page.waitForFunction(()=>Boolean(window.__menuMotion))
      const menuMotion=await page.evaluate(()=>window.__menuMotion)
      assert.ok(Math.abs(menuMotion.duration-.2)<.001)
      console.log('CSS-MENU',h5?'H5':'PC',JSON.stringify(menuMotion))
      const orderInput = page.getByRole('textbox', { name: '订单号' })
      await orderInput.fill('未提交搜索')
      const frame=page.locator('.layout-page-frame')
      assert.equal(await frame.getAttribute('data-nav-kind'),'tab','menu navigation is a peer transition')
      if (!h5) assert.equal(await orders.locator('xpath=ancestor-or-self::*[@role="menuitem"][1]').getAttribute('aria-current'),'location')
      const depth = await page.evaluate(() => history.length)
      await orders.click(); assert.equal(await page.evaluate(() => history.length), depth)
      assert.equal(await orderInput.inputValue(), '未提交搜索', 'same destination did not remount the page')
      await page.goBack(); await page.waitForURL(at('/product/list')); await add.waitFor()
      await page.goForward(); await page.waitForURL(at('/order/list'))
      await page.locator('.layout-page-frame > *').first().waitFor()
      await page.locator('.app-loading').waitFor({ state: 'hidden' })
    })

    await visit('slow page departure stays readable → browser back restores list scroll', {
      override: ({pathname}) => pathname === '/product/list' ? ok({records:Array.from({length:20},(_,i)=>({
        id:910000+i,name:'导航位置夹具 '+i,price:10,status:1
      })),total:20,current:1,size:20}) : undefined
    }, async page => {
      await page.setViewportSize({ width: h5 ? 390 : 1440, height: 460 })
      await page.goto(at('/product/list'), {waitUntil:'networkidle'})
      const main = page.locator('.layout-main')
      const add = page.getByRole('button',{name:'新增商品'})
      await add.focus()
      await page.waitForFunction(() => {
        const el=document.querySelector('.layout-main')
        return el && el.scrollHeight-el.clientHeight >= 80
      })
      const top=await main.evaluate(el=>{el.scrollTop=80;return el.scrollTop})
      let release
      const hold=new Promise(r=>{release=r})
      await page.route('**/assets/ProductForm-*.js', async route=>{await hold;await route.fallback()})
      try {
        await page.keyboard.press('Enter')
        const outgoing=main.locator('.layout-page-frame.viewport-departing')
        await outgoing.waitFor()
        assert.equal(await page.locator('.viewport-departing').count(),1)
        const style=await outgoing.evaluate(el=>({opacity:+getComputedStyle(el).opacity, duration:getComputedStyle(el).transitionDuration}))
        assert.ok(style.opacity >= .93 && Math.abs(parseFloat(style.duration)-.1) < .001)
        console.log('CSS-EXIT',h5?'H5':'PC',JSON.stringify(style),'scrollToRestore='+top)
      } finally { release() }
      await page.waitForURL(at('/product/create'))
      await page.goBack();await page.waitForURL(at('/product/list'))
      await page.waitForFunction(y=>Math.abs(document.querySelector('.layout-main')?.scrollTop-y)<2,top)
      assert.ok(await main.evaluate(el=>el.contains(document.activeElement)))
    })

    for (const reduced of [false, true]) {
      await visit('real page entry transition: ' + (reduced ? 'reduced motion' : 'normal motion'), { reduced }, async page => {
        await page.goto(at('/product/list'), { waitUntil: 'networkidle' })
        // Sample computed styles during the SPA's own transition (no injected
        // CSS, synthetic transition classes or sleeps that can miss the first frame).
        await page.evaluate(() => {
          const root = document.querySelector('.layout-main')
          window.__routeMotionSamples = []
          const obs = new MutationObserver(() => {
            const node = root.querySelector('.layout-page-frame.viewport-enter-active')
            if (!node) return
            const css = getComputedStyle(node)
            window.__routeMotionSamples.push({ kind: node.dataset.navKind, both: Boolean(document.querySelector('.page-viewport--shell.viewport-enter-active')), property: css.transitionProperty,
              ease: css.transitionTimingFunction, duration: Math.max(...css.transitionDuration.split(',').map(Number.parseFloat)),
              opacity: Number(css.opacity), transform: css.transform, frames: root.querySelectorAll('.layout-page-frame').length })
          })
          obs.observe(root, { childList: true, attributes: true, attributeFilter: ['class'], subtree: true })
        })
        await page.getByRole('button', { name: '新增商品' }).click()
        await page.getByPlaceholder('请输入商品名称').waitFor()
        await page.waitForFunction(() => window.__routeMotionSamples.length > 0)
        const samples = await page.evaluate(() => window.__routeMotionSamples)
        assert.ok(samples.every(s => s.frames === 1), 'no overlapping interactive page frames')
        assert.ok(samples.every(s => s.kind === 'forward' && !s.both && s.opacity > 0), 'one visible forward owner')
        if (reduced) assert.ok(samples.every(s => (s.property === 'none' || s.duration === 0) && s.opacity === 1 && s.transform === 'none'),
          'reduced motion does not fade or translate the incoming view')
        else assert.ok(samples.some(s => Math.abs(s.duration - 0.3) < .001 && s.property.includes('opacity')), 'forward uses the shared 300ms token')
        const draft = page.getByPlaceholder('请输入商品名称')
        await draft.fill('导航保留的草稿')
        if (!h5) {
          await draft.evaluate(el => {window.__beforeCollapse=el})
          await page.getByRole('button',{name:'收起导航'}).click()
          await page.getByRole('button',{name:'展开导航'}).click()
          assert.ok(await draft.evaluate(el=>el===window.__beforeCollapse),'sidebar collapse preserves form node')
          await draft.focus()
        }
        await draft.evaluate(el => { window.__draftInput = el; el.setSelectionRange(1,3); location.hash = 'draft' })
        await page.waitForURL(at('/product/create#draft'))
        await page.goBack(); await page.waitForURL(at('/product/create'))
        assert.equal(await draft.evaluate(el => el === window.__draftInput && document.activeElement === el && el.selectionStart === 1),true)
        assert.equal(await draft.inputValue(),'导航保留的草稿')
        await page.goBack(); await page.getByRole('button', {name:'新增商品'}).waitFor()
        await page.waitForFunction(() => window.__routeMotionSamples.some(s => s.kind === 'back'))
        const back = await page.evaluate(() => window.__routeMotionSamples.filter(s => s.kind === 'back'))
        assert.ok(back.every(s=>!s.both && s.frames===1 && s.opacity>0))
        if (!reduced) assert.ok(back.some(s=>Math.abs(s.duration-.24)<.001),'back uses the shorter token')
        const moving=samples.find(s=>s.duration>0) || samples[0]
        if (!reduced) assert.match(moving.ease,/cubic-bezier\(0\.16, 1, 0\.3, 1\)/)
        console.log('CSS-HISTORY',h5?'H5':'PC',JSON.stringify({reduced,enterSeconds:moving.duration,
          backSeconds:Math.max(...back.map(s=>s.duration)),ease:moving.ease,
          minSampledOpacity:Math.min(...samples.map(s=>s.opacity)),maxFrames:Math.max(...samples.map(s=>s.frames))}))
      })
    }

    const order = { id: 900001, orderNo: 'QA-ROUTE-900001', productName: '夹具订单商品', status: 'PAID', amount: 88, userId: 1 }
    const actions = []
    await visit('order number search → matching row → details/progress → close → reset', {
      override: ({ pathname, method, req }) => {
        if (method !== 'GET') return
        if (pathname === '/admin/order/list') {
          const filter = new URL(req.url()).searchParams.get('orderNo') || ''
          const records = filter === order.orderNo ? [order] : []
          actions.push(filter ? 'search:' + filter : 'unfiltered')
          return ok({ records, total: records.length })
        }
        if (pathname === '/admin/order/900001') { actions.push('detail'); return ok({ ...order, specInfo: '夹具专属规格' }) }
        if (pathname === '/admin/order/900001/progress') { actions.push('progress'); return ok([]) }
      }
    }, async page => {
      await page.goto(at('/order/list'), { waitUntil: 'networkidle' })
      const input = page.getByRole('textbox', { name: '订单号' })
      await input.fill(order.orderNo); await input.press('Enter')
      const row = page.getByRole('row').filter({ hasText: order.orderNo })
      await row.getByRole('button', { name: '详情' }).click()
      const dialog = page.getByRole('dialog', { name: '订单详情' })
      await dialog.getByText('夹具专属规格', { exact: true }).waitFor()
      await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' })
      await page.getByRole('button', { name: '重置', exact: true }).click()
      await row.waitFor({ state: 'hidden' })
      assert.deepEqual(actions, ['unfiltered', 'search:' + order.orderNo, 'detail', 'progress', 'unfiltered'])
    })

    for (const [kind, options, start] of [
      ['permission', { scenario: 'permission-denied' }, '/product/list'],
      ['role', { auth: 'cs' }, '/system/admin'],
      ['entitlement', { scenario: 'feature-denied' }, '/platform/income-analysis'],
      ['not-found', {}, '/__route_journey_missing__']
    ]) {
      await visit(kind + ' rejection/missing page → home', options, async page => {
        await page.goto(at(start))
        const missing = kind === 'not-found'
        await page.getByRole('heading', { name: missing ? '404' : '403', exact: true }).waitFor()
        assert.equal(new URL(page.url()).pathname, prefix + (missing ? start : '/403'))
        await page.getByRole('button', { name: h5 ? '返回工作台' : '返回首页' }).click()
        await page.waitForURL(at('/dashboard'))
        await page.locator('.error-page').waitFor({ state: 'detached' })
        await page.locator('.layout-page-frame > *').first().waitFor()
      })
    }

    let release, reads = 0
    const gate = new Promise(resolve => { release = resolve })
    await visit('held product read → regional feedback → HTTP 503 → retry recovers seeded row', {
      reduced: true, theme: 'neumorphism',
      override: async ({ pathname, method }) => {
        if (pathname === '/product/list' && method === 'GET') {
          reads++
          if (reads === 1) await gate
          if (reads === 1 || reads === 3) return { status: 503, body: { code: 503, msg: '合成慢网错误' } }
        }
      }
    }, async page => {
      try {
        await page.goto(at('/product/list'), { waitUntil: 'domcontentloaded' })
        await page.locator('.el-table .el-loading-mask').waitFor()
      } finally { release() }
      const alert = page.locator('.load-feedback--error')
      const row = page.getByRole('row').filter({ hasText: '合成测试服务' }).first()
      const retry = alert.getByRole('button', { name: '重新加载页面' })
      await alert.getByText(/合成慢网错误/).waitFor()
      await page.locator('.el-table').getByText('商品列表未能加载', { exact: true }).waitFor()
      await retry.click()
      await row.waitFor()
      await alert.waitFor({ state: 'hidden' })
      assert.equal(reads, 2, 'one failed read and one retry')
      await page.getByRole('button', { name: '查询', exact: true }).click()
      await alert.getByText(/合成慢网错误/).waitFor()
      assert.ok(await row.isVisible(), 'failed refresh preserves the last successful rows')
      await retry.click()
      await row.waitFor()
      await alert.waitFor({ state: 'hidden' })
      assert.equal(reads, 4)
    })
    let orderReads = 0
    await visit('order business rejection in HTTP 200 → local alert → retry', {
      reduced: true, override: ({ pathname, method }) => {
        if (method !== 'GET' || pathname !== '/admin/order/list') return
        if (++orderReads === 1) return { status: 200, body: { code: 422, msg: '合成订单读取失败' } }
        return ok({ records: [order], total: 1 })
      }
    }, async page => {
      await page.goto(at('/order/list'))
      const alert = page.getByRole('alert').filter({ hasText: '合成订单读取失败' })
      await alert.getByRole('button', { name: '重试', exact: true }).click()
      await page.getByRole('row').filter({ hasText: order.orderNo }).waitFor()
      await alert.waitFor({ state: 'hidden' })
      assert.equal(orderReads, 2)
    })
    for (const section of ['product', 'order']) {
      let release, held = 0
      const gate = new Promise(resolve => { release = resolve })
      const api = section === 'product' ? '/product/list' : '/admin/order/list'
      await visit('feedback ownership: ' + section + ' table', {
        reduced: true, override: async ({ pathname, method }) => {
          if (method === 'GET' && pathname === api) { held++; await gate }
        }
      }, async page => {
        try {
          await page.goto(at('/' + section + '/list'), { waitUntil: 'domcontentloaded' })
          const mask = page.locator('.el-table .el-loading-mask')
          await mask.waitFor()
          const seen = await mask.evaluate(async el => {
            const until = performance.now() + 600 // exceeds the intentional delayed-progress threshold
            const visible = n => !!n?.getClientRects().length && getComputedStyle(n).visibility !== 'hidden'
            let localFrames = 0, doubleFrames = 0
            do {
              await new Promise(r => requestAnimationFrame(r))
              if (visible(el)) {
                localFrames++
                if (visible(document.querySelector('.app-loading'))) doubleFrames++
              }
            } while (performance.now() < until)
            const icon = el.querySelector('.circular'), css = icon && getComputedStyle(icon)
            return { localFrames, doubleFrames, staticIndicator: visible(icon) &&
              (css.animationName === 'none' || (css.animationIterationCount === '1' && css.animationDuration.endsWith('s') && parseFloat(css.animationDuration) <= 0.001)) }
          })
          console.log('REGION', h5 ? 'H5' : 'PC', section, JSON.stringify(seen))
          assert.ok(seen.localFrames && seen.staticIndicator, 'reduced motion retains a visible static local indicator')
          assert.equal(seen.doubleFrames, 0, 'local loading mask and global bar visible on the same frame')
        } finally { release() }
        await page.locator('.el-table .el-loading-mask').waitFor({ state: 'hidden' })
        assert.equal(held, 1)
      })
    }
    let finishCategories, categoryReads = 0
    const categoriesGate = new Promise(resolve => { finishCategories = resolve })
    await visit('unmasked category read retains top fallback', {
      reduced: true, override: async ({ pathname }) => {
        if (pathname === '/product/category/all') { categoryReads++; await categoriesGate }
      }
    }, async page => {
      try {
        await page.goto(at('/product/list'))
        await page.getByText('合成测试服务', { exact: true }).first().waitFor()
        await page.locator('.el-loading-mask').waitFor({ state: 'hidden' })
        await page.locator('.app-loading').getByText('正在加载数据').waitFor({ state: 'attached' })
        const bar = page.locator('.app-loading__bar')
        assert.ok(await bar.isVisible())
        assert.equal(await bar.evaluate(el => getComputedStyle(el).animationName), 'none')
        assert.equal(categoryReads, 1)
      } finally { finishCategories() }
      await page.locator('.app-loading').waitFor({ state: 'hidden' })
    })

    let finishList
    const listGate = new Promise(resolve => { finishList = resolve })
    await visit('slow route chunk rejects → explicit navigation error → full reload recovers target', {
      reduced: true, expectedAssetFailure: (p, status) => /^\/assets\/ProductForm-[^/]+\.js$/.test(p) && status === 503,
      override: async ({ pathname }) => { if (pathname === '/product/list') await listGate }
    }, async page => {
      let releaseChunk, rejected = 0
      const gate = new Promise(resolve => { releaseChunk = resolve })
      // Page routes take precedence over context routes; fallback retains full isolation.
      await page.route('**/assets/ProductForm-*.js', async route => {
        assert.equal(new URL(route.request().url()).origin, origin)
        if (rejected++) return route.fallback()
        await gate
        return route.fulfill({ status: 503, contentType: 'application/javascript', body: '' })
      })
      try {
        await page.goto(at('/product/list'))
        const mask = page.locator('.el-table .el-loading-mask')
        await mask.waitFor()
        await page.getByRole('button', { name: '新增商品' }).click()
        await page.locator('.app-loading').getByText('正在切换页面', { exact: true }).waitFor({ state: 'attached' })
        const bar = page.locator('.app-loading__bar')
        assert.ok(await bar.isVisible() && await mask.isVisible(), 'a separate pending navigation still owns route progress')
        assert.equal(await bar.evaluate(el => getComputedStyle(el).animationName), 'none', 'static route progress in reduced motion')
      } finally { releaseChunk(); finishList() }
      const error = page.locator('.app-route-error')
      await error.waitFor()
      await error.getByRole('button', { name: '刷新应用' }).click()
      await page.waitForURL(at('/product/create'))
      await page.getByPlaceholder('请输入商品名称').waitFor()
      assert.ok(rejected >= 2)
      assert.equal(await error.count(), 0)
    })
  }
  console.log('INTERACTIONS: %d/%d', passed, passed + failed.length)
  assert.ok(passed + failed.length, 'no matching journeys')
  return failed
}
