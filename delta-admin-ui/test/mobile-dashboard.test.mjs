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
  for (const label of ['今日数据', '我的商品', '员工与客户', '客户与服务', '快捷工具']) {
    assert.match(mobile, new RegExp(label))
  }
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
  assert.match(mobile, /const claimed = new Set/)
  assert.match(mobile, /grid-template-columns:repeat\(3,minmax\(0,1fr\)\)/)
})

test('mobile admin shell keeps a single-row header and fixed four-item bottom navigation', async () => {
  const layout = await read('src/layout/index.vue')
  const sidebar = await read('src/layout/SidebarNavigation.vue')
  assert.match(layout, /class="mobile-page-title"/)
  assert.match(layout, /class="mobile-bottom-nav"/)
  for (const label of ['工作台', '订单', '待办', '菜单']) {
    assert.match(layout, new RegExp(label))
  }
  assert.match(layout, /flex-wrap: nowrap/)
  assert.match(layout, /calc\(82px\s*\+\s*var\(--app-safe-bottom\)\)/)
  assert.match(layout, /<SidebarNavigation mobile/)
  assert.match(sidebar, /admin-navigation\.mobile/)
})

test('mobile dashboard exposes role-filtered product and operations shortcuts', async () => {
  const mobile = await read('src/views/dashboard/MobileDashboard.vue')
  for (const route of ['/product/list', '/product/category', '/product/create', '/player', '/user/list', '/chat/service']) {
    assert.match(mobile, new RegExp(route.replaceAll('/', '\\/')))
  }
  assert.match(mobile, /if \(!props\.isAdmin \|\| !can\('product\.manage'\)\) return \[\]/)
})
