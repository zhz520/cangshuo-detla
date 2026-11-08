import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('compact admin and CS screens use the dedicated mobile operations dashboard', async () => {
  const dashboard = await read('src/views/dashboard/index.vue')
  const mobile = await read('src/views/dashboard/MobileDashboard.vue')

  assert.match(dashboard, /<MobileDashboard v-if="isCompact"/)
  assert.match(dashboard, /<div v-else class="dashboard-desktop">/)
  assert.match(mobile, /运营管理后台/)
  assert.match(mobile, /客服工作台/)
  for (const label of ['今日待办', '待指派', '退款审批', '投诉处理', '提现审核', '在线打手', '离线打手', '冻结打手', '当班客服']) {
    assert.match(mobile, new RegExp(label))
  }
  for (const label of ['今日数据', '我的商品']) {
    assert.match(mobile, new RegExp(label))
  }
  assert.match(mobile, /v-for="group in operationGroups"/)
  assert.match(mobile, /class="status-grid"/)
  assert.match(mobile, /class="today-grid"/)
  assert.match(mobile, /class="icon-grid"|class="icon-grid product-grid"/)
  assert.doesNotMatch(mobile, /class="mobile-hero"/)
})

test('mobile dashboard prioritizes order data and uses live analytics and player drilldown APIs', async () => {
  const mobile = await read('src/views/dashboard/MobileDashboard.vue')
  assert.match(mobile, /getDashboardAnalytics/)
  assert.match(mobile, /getDashboardPlayers/)
  for (const label of ['访客数', '登录日活', '有效订单', '已收款']) {
    assert.match(mobile, new RegExp(label))
  }
  assert.match(mobile, /openPlayers\('ONLINE'\)/)
  assert.match(mobile, /openPlayers\('OFFLINE'\)/)
  assert.match(mobile, /openPlayers\('FROZEN'\)/)
  assert.match(mobile, /openTasks\(item\.kind\)/)
  assert.match(mobile, /buildMobileNavigationSections\(groups,/)
  assert.match(mobile, /grid-template-columns:repeat\(3,minmax\(0,1fr\)\)/)
})

test('mobile admin shell removes the menu drawer and keeps three direct shortcuts', async () => {
  const layout = await read('src/layout/index.vue')
  assert.match(layout, /class="mobile-page-title"/)
  assert.match(layout, /class="mobile-bottom-nav"/)
  for (const label of ['工作台', '订单', '待办']) {
    assert.match(layout, new RegExp(label))
  }
  assert.match(layout, /flex-wrap: nowrap/)
  assert.match(layout, /calc\(82px\s*\+\s*var\(--app-safe-bottom\)\)/)
  assert.match(layout, /<button v-if="!isCompact" class="collapse-btn"/)
  assert.match(layout, /grid-template-columns: repeat\(3, minmax\(0, 1fr\)\)/)
  assert.doesNotMatch(layout, /class="mobile-navigation"/)
  assert.match(layout, /v-if="can\('order\.read'\)"[^>]*@click="router\.push\(adminPath\('\/order\/list'\)\)"/)
  assert.match(layout, /mobile-bottom-nav\.two-items/)
})

test('list pages inherit the automatic mobile table-to-card adapter', async () => {
  const layout = await read('src/layout/index.vue')
  const responsive = await read('src/styles/responsive.css')
  const adapter = await read('src/utils/mobile-table-cards.js')
  assert.match(layout, /installMobileTableCards\(main\.value\?\.\$el\)/)
  assert.match(adapter, /dataset\.mobileLabel/)
  assert.match(adapter, /MutationObserver/)
  assert.match(responsive, /\.el-table\.mobile-card-table/)
  assert.match(responsive, /grid-template-columns:repeat\(2,minmax\(0,1fr\)\)/)
})

test('mobile dashboard builds all operation shortcuts from the same role and permission navigation', async () => {
  const mobile = await read('src/views/dashboard/MobileDashboard.vue')
  for (const route of ['/product/list', '/product/category', '/product/create']) {
    assert.match(mobile, new RegExp(route.replaceAll('/', '\\/')))
  }
  assert.match(mobile, /if \(!props\.isAdmin \|\| !can\('product\.manage'\)\) return \[\]/)
  assert.match(mobile, /buildNavigation\(router\.options\.routes, role, can, platformFeatureAllowed\)/)
  assert.match(mobile, /const role = props\.isAdmin \? 'admin' : 'cs'/)
})
