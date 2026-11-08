import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildMobileAdminRoutes,
  adminLoginPath,
  canonicalAdminPath,
  isMobileAdminPath,
  mobileAdminPath,
  shouldUseMobileRoute
} from '../src/utils/mobile-route.js'

test('mobile admin paths use a stable /mobile directory and preserve deep links', () => {
  assert.equal(mobileAdminPath('/'), '/mobile')
  assert.equal(mobileAdminPath('/dashboard'), '/mobile/dashboard')
  assert.equal(mobileAdminPath('/order/list?status=PAID'), '/mobile/order/list?status=PAID')
  assert.equal(mobileAdminPath('/mobile/order/list'), '/mobile/order/list')
  assert.equal(canonicalAdminPath('/mobile'), '/')
  assert.equal(canonicalAdminPath('/mobile/order/list'), '/order/list')
  assert.equal(isMobileAdminPath('/mobile/wechat-binding'), true)
  assert.equal(isMobileAdminPath('/mobiles'), false)
})

test('mobile devices are redirected once while login and error pages stay reachable', () => {
  assert.equal(shouldUseMobileRoute('/dashboard', true), true)
  assert.equal(shouldUseMobileRoute('/dashboard', false), false)
  assert.equal(shouldUseMobileRoute('/mobile/dashboard', true), false)
  assert.equal(shouldUseMobileRoute('/login', true), false)
  assert.equal(shouldUseMobileRoute('/mobile/login', true), false)
  assert.equal(shouldUseMobileRoute('/403', true), false)
  assert.equal(adminLoginPath(true), '/mobile/login')
  assert.equal(adminLoginPath(false), '/login')
})

test('mobile route aliases reuse admin screens with unique names and catch unknown paths', () => {
  const component = () => null
  const source = [
    { path: '/login', component },
    { path: '/', redirect: '/dashboard', component, children: [{ path: 'dashboard', name: 'Dashboard', component }] },
    { path: '/order', redirect: '/order/list', component, children: [{ path: 'list', name: 'OrderList', component }] }
  ]
  const routes = buildMobileAdminRoutes(source)
  assert.ok(routes.some(route => route.path === '/mobile' && route.redirect === '/mobile/dashboard'))
  assert.ok(routes.some(route => route.path === '/mobile/order' && route.redirect === '/mobile/order/list'))
  assert.ok(routes.some(route => route.children?.some(child => child.name === 'MobileOrderList')))
  assert.ok(routes.some(route => route.name === 'MobileNotFound'))
  assert.equal(routes.some(route => route.path === '/mobile/login'), false)
})
