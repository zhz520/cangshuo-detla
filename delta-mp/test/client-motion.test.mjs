import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { useHomeRecommendations } from '../composables/useHomeRecommendations.js'
import { useHomeNotices } from '../composables/useHomeNotices.js'
import { usePagedList } from '../composables/usePagedList.js'
import { enableChromiumKeyboardResize, viewportMetrics } from '../utils/viewport.js'
import { routeMotion, installPageMotion, navigateTabWithTransition } from '../utils/page-motion.mjs'

test('only identified Chromium 108+ receives keyboard extension, once', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8')
  assert.doesNotMatch(html, /interactive-widget/)
  const meta = { content: 'width=device-width, viewport-fit=cover' }
  for (const brands of [undefined, [{ brand: 'Chromium', version: '107' }], [{ brand: 'Safari', version: '200' }]]) enableChromiumKeyboardResize(meta, brands)
  assert.equal(meta.content, 'width=device-width, viewport-fit=cover')
  for (let i=0; i<2; i++) enableChromiumKeyboardResize(meta, [{ brand: 'Chromium', version: '108' }])
  assert.equal(meta.content.split('interactive-widget').length, 2)
  for (const height of [480, 820]) {
    const m = viewportMetrics({ width: 390, height, visualHeight: 480, baseline: 820, focused: true })
    assert.equal(m.keyboardOpen, true)
    assert.equal(m.visibleHeight, 480)
  }
})

test('recommendations keep selection on return, ignore superseded responses and retain cards on refresh failure', async () => {
  const calls = []
  const r = useHomeRecommendations({ getRecommendProducts: params => new Promise((resolve,reject) => calls.push({resolve,reject,params})),
    getRecommendCategories: async () => ({data:[{id:1},{id:2}]}), productSortParams: sort => ({sort}) })
  r.switchRecommendCategory('2')
  r.chooseHomeSort('sales')
  calls[1].resolve({data:[{id:2}]})
  calls[0].resolve({data:[{id:1}]})
  await Promise.resolve()
  assert.deepEqual(r.recommendProducts.value,[{id:2}])
  const reload = r.loadRecommendations({})
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(calls[2].params,{sort:'sales',categoryId:'2'})
  assert.equal(r.recommendLoading.value,true)
  calls[2].reject(Error('offline'))
  await reload
  assert.deepEqual(r.recommendProducts.value,[{id:2}])
  assert.ok(r.recommendError.value)
})

test('notice acknowledgement follows config and revision; tab return keeps session and open detail', async () => {
  const storage = new Map(); let shown = false; let rev = 1
  const site = { noticePopupEveryVisit: true, hasShownNoticePopupThisSession:()=>shown, markNoticePopupShownThisSession:()=>{shown=true} }
  const n = useHomeNotices({siteStore:site,clientStorage:storage, productDescriptionHtml:String,
    getActiveNotices:async()=>({data:[{id:1,popupDisplay:1,updatedAt:rev}]})})
  let release
  const load = n.loadNotices({},new Promise(r=>{release=r}))
  await Promise.resolve(); assert.equal(n.showNoticePopup.value,false)
  release(); await load; assert.equal(n.showNoticePopup.value,true)
  n.closeNoticePopup(); await n.loadNotices({},true); assert.equal(n.showNoticePopup.value,false)
  site.noticePopupEveryVisit=false; await n.loadNotices({},true); assert.equal(n.showNoticePopup.value,false)
  rev++; await n.loadNotices({},true); assert.equal(n.showNoticePopup.value,true)
  n.openNoticeDetail({id:3}); await n.loadNotices({},true); assert.equal(n.popupNotices.value[0].id,3)
})

test('pager reports one phase and keeps existing rows until refresh commits', async () => {
  let resolve
  const p=usePagedList({pageSize:1,fetchPage:()=>new Promise(r=>{resolve=r})})
  async function settle(job,id) { await Promise.resolve(); resolve({records:[{id}],total:2}); await job }
  let job=p.refresh(); assert.equal(p.phase.value,'initial'); await settle(job,1)
  job=p.refresh(); assert.equal(p.phase.value,'refresh'); assert.equal(p.items.value[0].id,1); await settle(job,1)
  job=p.loadMore(); assert.equal(p.phase.value,'more'); await settle(job,2)
  assert.equal(p.items.value.length,2)
})

test('navigation differentiates history pop, registered tab, forward and cold entry', () => {
  const from={matched:[{}]}, to={meta:{}}
  assert.equal(routeMotion(to,from,1,2),'back')
  assert.equal(routeMotion(to,from,3,2),'enter')
  assert.equal(routeMotion({meta:{isTabBar:true}},from,2,2),'tab')
  assert.equal(routeMotion(to,undefined,0,null),'enter')
})

test('cached H5 navigation ignores hidden content, superseded routes and filter replacement', () => {
  const attrs=new Map(), hidden={getClientRects:()=>[]}
  const pane={dataset:{deltaContent:'tab'},getClientRects:()=>[{}],setAttribute:(k,v)=>attrs.set(k,v),removeAttribute:k=>attrs.delete(k)}
  const body={firstElementChild:{children:[]},querySelectorAll:()=>[hidden,pane]}
  const page={dataset:{page:'orders'},getClientRects:()=>[{}],querySelector:()=>body}
  let next, frame, count=0, stopped=false
  const router={currentRoute:{value:null},afterEach:cb=>{next=cb;return ()=>{stopped=true}}}
  const win={history:{state:{position:1}},requestAnimationFrame:cb=>{frame=cb;return ++count},cancelAnimationFrame:()=>{frame=null},
    clearTimeout(){},matchMedia:()=>({matches:false})}
  const stop=installPageMotion(router,{documentElement:{dataset:{}},querySelectorAll:()=>[page]},win)
  const route=(path,fullPath=path)=>({path,fullPath,meta:{route:path},matched:[{}]})
  next(route('skip'),route('home'))
  next(route('orders'),route('skip'))
  frame()
  assert.equal(attrs.get('data-delta-enter'),'tab')
  next(route('orders','orders?q=filter'),route('orders'))
  next(route('blocked'),route('orders'),new Error('guard cancelled'))
  assert.equal(count,2)
  win.history.state.position=0
  next(route('orders'),route('detail'));frame()
  assert.equal(attrs.get('data-delta-enter'),'back')
  stop();assert.equal(stopped,true);assert.equal(attrs.size,0)
})

test('tab navigation holds the outgoing frame until the new page has painted', async () => {
  const root={dataset:{}}
  const doc={documentElement:root,startViewTransition(update){
    const done=Promise.resolve(update())
    return {finished:done,updateCallbackDone:done}
  }}
  const win={matchMedia:()=>({matches:false}),setTimeout:(cb)=>{setTimeout(cb,0);return 1}}
  let calls=0
  const result=navigateTabWithTransition(()=>{calls++;return Promise.resolve('mounted')},doc,win)
  assert.equal(result.started,true)
  assert.equal(root.dataset.deltaView,'tab')
  await Promise.resolve()
  assert.equal(calls,1)
  await result.settled
  await Promise.resolve()
  assert.equal(root.dataset.deltaView,undefined)
})

test('tab navigation falls back without a View Transition or with reduced motion', () => {
  let calls=0
  const navigate=()=>{calls++;return Promise.resolve()}
  const reduced=navigateTabWithTransition(navigate,{documentElement:{dataset:{}}},{matchMedia:()=>({matches:true})})
  assert.equal(reduced.started,false)
  assert.equal(calls,1)
  assert.equal(reduced.settled instanceof Promise,true)
  const legacy=navigateTabWithTransition(navigate,{documentElement:{dataset:{}}},{matchMedia:()=>({matches:false})})
  assert.equal(legacy.started,false)
  assert.equal(calls,2)
})

test('a superseded tab transition cannot clear the newer transition marker', async () => {
  const root={dataset:{}}, finish=[]
  const doc={documentElement:root,startViewTransition(update){
    update()
    return {finished:new Promise(resolve=>finish.push(resolve)),updateCallbackDone:Promise.resolve()}
  }}
  const win={matchMedia:()=>({matches:false}),setTimeout:cb=>{cb();return 1}}
  navigateTabWithTransition(()=>Promise.resolve(),doc,win)
  navigateTabWithTransition(()=>Promise.resolve(),doc,win)
  finish[0]()
  await Promise.resolve()
  assert.equal(root.dataset.deltaView,'tab')
  finish[1]()
  await Promise.resolve()
  assert.equal(root.dataset.deltaView,undefined)
})
