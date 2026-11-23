import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { useHomeRecommendations } from '../composables/useHomeRecommendations.js'
import { useHomeNotices } from '../composables/useHomeNotices.js'
import { usePagedList } from '../composables/usePagedList.js'
import { enableChromiumKeyboardResize, viewportMetrics } from '../utils/viewport.js'
import { routeMotion } from '../utils/page-motion.mjs'

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
