import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { h5RedirectTarget } from '../src/utils/h5-route.js'

const read = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8')

test('H5 uses its own mobile shell and does not wrap the PC layout', () => {
  const layout = read('layout/H5Layout.vue')
  assert.match(layout, /class="h5-shell"/)
  assert.match(layout, /class="h5-header"/)
  assert.match(layout, /class="h5-back"/)
  assert.match(layout, /class="h5-bottom-nav"/)
  assert.match(layout, /<router-view/)
  assert.match(layout, /installMobileTableCards\(main\.value\)/)
  assert.match(layout, /dataset\.adminShell = 'h5'/)
  assert.doesNotMatch(layout, /LayoutShell/)
})

test('H5 and PC error pages keep the application shell and return to the right home', () => {
  const router = read('router/index.js')
  assert.match(router, /const h5Root = h5Routes\.find/)
  assert.match(router, /path: '403',\s+name: 'H5Forbidden'/)
  assert.match(router, /path: ':pathMatch\(\.\*\)\*',\s+name: 'H5NotFound'/)
  assert.match(router, /errorPage: true, hidden: true/)
  for (const page of ['403', '404']) {
    const source = read(`views/error/${page}.vue`)
    assert.match(source, /isH5Path\(route\.path\)/)
    assert.match(source, /\/h5\/dashboard/)
    assert.match(source, /返回工作台/)
  }
})

test('canonical links push an H5 history entry so browser and shell back both work', () => {
  const target = h5RedirectTarget({ path: '/product/list' }, { path: '/h5/dashboard' })
  assert.equal(target.path, '/h5/product/list')
  assert.equal(target.replace, undefined)
  const chatList = read('views/chat/ChatRecordList.vue')
  const chatDetail = read('views/chat/ChatRecordDetail.vue')
  assert.match(chatList, /isH5Path\(route\.path\)/)
  assert.match(chatList, /h5Path\(`\/chat\/record\/\$\{row\.id\}`\)/)
  assert.match(chatDetail, /\/h5\/chat\/record/)
})

test('mobile tables fill the content column and narrow H5 modules use two columns', () => {
  const responsive = read('styles/responsive.css')
  const dashboard = read('views/dashboard/MobileDashboard.vue')
  assert.match(responsive, /\.mobile-card-table \.el-scrollbar__view,[\s\S]*display:block!important; width:100%!important/)
  assert.match(responsive, /\.layout-main \.el-row \{ margin-left: 0 !important; margin-right: 0 !important; \}/)
  assert.match(dashboard, /@media\(max-width:430px\)[\s\S]*\.icon-grid \{ grid-template-columns:repeat\(2,minmax\(0,1fr\)\)/)
})
