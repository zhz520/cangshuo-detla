import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('customer-service client exposes quiet shift and permission APIs', async () => {
  const api = await read('pages-cs/api/cs.js')
  assert.match(api, /export const getCsPermissions = \(\) => get\('\/cs\/permissions'/,)
  assert.match(api, /export const getCsShiftStatus = \(\) => get\('\/cs\/shift\/status'/,)
  assert.match(api, /export const toggleCsShift = \(online\) => post\('\/cs\/shift\/toggle', \{ online: Boolean\(online\) \}/,)
  assert.match(api, /const quiet = \{ role: 'cs', loading: false, silent: true \}/)
})

test('mobile workbench provides retryable cached dashboard loading', async () => {
  const source = await read('pages-cs/dashboard/index.vue')
  assert.match(source, /onPullDownRefresh/)
  assert.match(source, /uni\.stopPullDownRefresh\(\)/)
  assert.match(source, /dashboardCache/)
  assert.match(source, /dashboardError/)
  assert.match(source, /@click="loadDashboard\(true\)"/)
  assert.match(source, /formatMetric\(firstNumber\(data\.todayVisitors/)
  assert.match(source, /skeleton-panel/)
})

test('mobile workbench keeps the requested roles, metrics and management hierarchy', async () => {
  const source = await read('pages-cs/dashboard/index.vue')
  for (const label of ['待指派', '服务中', '待审核', '退款审批', '投诉']) {
    assert.match(source, new RegExp(label))
  }
  for (const label of ['访客数', '登录日活', '有效订单', '已收款', '打手在线', '在线', '离线']) {
    assert.match(source, new RegExp(label))
  }
  for (const label of ['待确认订单', '提现审核', '打手管理', '客户管理', '商品管理', '公告管理', '员工与角色', '客户咨询']) {
    assert.match(source, new RegExp(`label:\\s*'${label}'`))
  }
  assert.match(source, /入驻、素材与履约统一入口/)
})

test('shift controls are CS-only while administrators receive a read-only count', async () => {
  const dashboard = await read('pages-cs/dashboard/index.vue')
  assert.match(dashboard, /v-if="!isAdmin" class="shift-panel"/)
  assert.match(dashboard, /v-else class="shift-panel admin"/)
  assert.match(dashboard, /onDutyCount/)
  assert.match(dashboard, /@click="toggleShift"/)

  const mine = await read('pages-cs/mine/index.vue')
  assert.match(mine, /v-if="!isAdmin"/)
  assert.match(mine, /getCsShiftStatus/)
  assert.match(mine, /toggleCsShift/)
})

test('mobile workbench filters privileged modules and avoids horizontal overflow', async () => {
  const dashboard = await read('pages-cs/dashboard/index.vue')
  const mine = await read('pages-cs/mine/index.vue')
  assert.match(dashboard, /return groups[\s\S]*?\.filter\(group => group\.items\.length\)/)
  assert.match(dashboard, /item\.allowed !== false/)
  assert.match(mine, /menus\.filter\(item => can\(item\.permission\)\)/)
  assert.match(dashboard, /overflow-x:\s*hidden/)
  assert.match(mine, /overflow-x:\s*hidden/)
})

test('mobile staff dashboard uses compact grouped cards and product shortcuts', async () => {
  const dashboard = await read('pages-cs/dashboard/index.vue')
  const mine = await read('pages-cs/mine/index.vue')
  assert.match(dashboard, /const productActions/)
  assert.match(dashboard, /productActions\.length/)
  assert.match(dashboard, /title-count/)
  assert.match(dashboard, /v-for="group in actionGroups"/)
  assert.match(dashboard, /mobile-action-grid/)
  assert.match(dashboard, /background:#fff/)
  assert.match(dashboard, /grid-template-columns:repeat\(4,minmax\(0,1fr\)\)/)
  assert.match(mine, /group-card/)
  assert.match(mine, /menu-grid/)
  assert.match(mine, /service-grid/)
})

test('high-frequency dispatch and order detail paths reduce navigation', async () => {
  const orderList = await read('pages-cs/order/list.vue')
  const assign = await read('pages-cs/order/assign.vue')
  const detail = await read('pages-cs/order/detail.vue')
  assert.match(orderList, /onLoad\(options/)
  assert.match(orderList, /待指派订单优先展示/)
  assert.match(orderList, /callUser\(o\)/)
  assert.match(assign, /onlineOnly/)
  assert.match(assign, /在线可派/)
  assert.match(assign, /查看全部打手/)
  assert.match(detail, /onShow\(\(\)\s*=>\s*\{[\s\S]*loadDetail\(\)/)
  assert.match(detail, /position:fixed/)
  assert.match(detail, /callCustomer/)
})

test('mobile staff navigation contains no duplicate module destinations', async () => {
  for (const file of ['pages-cs/dashboard/index.vue', 'pages-cs/mine/index.vue', 'pages/workbench/index.vue']) {
    const source = await read(file)
    const routes = [...source.matchAll(/(?:route|pagePath):\s*'([^']+)'/g)].map(match => match[1])
    assert.equal(new Set(routes).size, routes.length, `${file} contains a repeated destination`)
  }
})

test('fulfillment review keeps both task types paginated and passes the relay player id', async () => {
  const fulfillment = await read('pages-cs/fulfillment/list.vue')
  const relay = await read('pages-cs/relay/list.vue')
  const workbench = await read('pages/workbench/index.vue')

  assert.match(fulfillment, /pageSize=30/)
  assert.match(fulfillment, /getCsRelayList/)
  assert.match(fulfillment, /getCsReplaceList/)
  assert.match(fulfillment, /taskId:focusTaskId\.value/)
  assert.match(fulfillment, /page\.value\+=1/)
  assert.match(relay, /\{ newPlayerId: p\.id \}/)
  assert.match(workbench, /onLoad\(options/)
  assert.match(workbench, /kind: kind\.value/)
  assert.match(workbench, /type=' \+ task\.kind\.toLowerCase\(\) \+ '&taskId='/)
})

test('mobile workbench exposes daily active users and uses valid review destinations', async () => {
  const dashboard = await read('pages-cs/dashboard/index.vue')
  assert.match(dashboard, /登录日活/)
  assert.match(dashboard, /todayActiveUsers/)
  assert.match(dashboard, /\/pages\/workbench\/index\?kind=ENROLLMENT/)
  assert.match(dashboard, /\/pages\/workbench\/index\?kind=MATERIAL/)
  assert.doesNotMatch(dashboard, /tab=application|tab=materials/)
})
