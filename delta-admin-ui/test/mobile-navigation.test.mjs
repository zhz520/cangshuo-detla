import test from 'node:test'
import assert from 'node:assert/strict'
import { buildNavigation } from '../src/utils/navigation.js'
import { buildMobileNavigationSections } from '../src/utils/mobile-navigation.js'

const routes = [
  {
    path: '/',
    children: [
      { path: 'dashboard', meta: { title: '仪表盘', icon: 'Odometer', roles: ['admin', 'cs'] } },
      { path: 'system/admin', meta: { title: '管理员管理', icon: 'User', roles: ['admin'], permission: 'system.manage' } }
    ]
  },
  {
    path: '/order',
    children: [
      { path: 'list', meta: { title: '订单管理', icon: 'List', roles: ['admin', 'cs'], permission: 'order.read' } },
      { path: 'refund', meta: { title: '退款审批', icon: 'Money', roles: ['admin', 'cs'], permission: 'order.refund' } }
    ]
  },
  {
    path: '/withdraw',
    children: [
      { path: '', meta: { title: '提现审核', icon: 'Wallet', roles: ['admin'], permission: 'withdraw.review' } }
    ]
  }
]

test('mobile navigation sections contain only routes granted to the current CS account', () => {
  const granted = new Set(['order.read', 'order.manage'])
  const groups = buildNavigation(routes, 'cs', code => granted.has(code), () => true)
  const sections = buildMobileNavigationSections(groups)
  const routesShown = sections.flatMap(section => section.items.map(item => item.route))

  assert.deepEqual(routesShown.sort(), ['/order/list'])
  assert.ok(!routesShown.includes('/dashboard'))
  assert.ok(!routesShown.includes('/system/admin'))
  assert.ok(!routesShown.includes('/withdraw'))
})

test('mobile workbench removes claimed shortcuts and never repeats a destination', () => {
  const groups = buildNavigation(routes, 'admin', () => true, () => true)
  const sections = buildMobileNavigationSections(groups, ['/order/list', '/order/refund'])
  const routesShown = sections.flatMap(section => section.items.map(item => item.route))

  assert.ok(!routesShown.includes('/dashboard'))
  assert.ok(!routesShown.includes('/order/list'))
  assert.ok(!routesShown.includes('/order/refund'))
  assert.equal(new Set(routesShown).size, routesShown.length)
})
