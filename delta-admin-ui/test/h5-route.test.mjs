import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  buildH5Routes,
  canonicalAdminPath,
  h5RedirectTarget,
  h5Path,
  isH5Path
} from '../src/utils/h5-route.js'

test('H5 admin paths use a stable /h5 directory and preserve canonical paths', () => {
  assert.equal(h5Path('/'), '/h5')
  assert.equal(h5Path('/dashboard'), '/h5/dashboard')
  assert.equal(h5Path('/order/list?status=PAID'), '/h5/order/list?status=PAID')
  assert.equal(h5Path('/h5/order/list'), '/h5/order/list')
  assert.equal(canonicalAdminPath('/h5'), '/')
  assert.equal(canonicalAdminPath('/h5/order/list'), '/order/list')
  assert.equal(isH5Path('/h5/wechat-binding'), true)
  assert.equal(isH5Path('/h5x'), false)
})

test('H5 routes reuse admin screens with unique names and no login alias', () => {
  const component = () => null
  const layout = () => null
  const source = [
    { path: '/login', component },
    { path: '/', redirect: '/dashboard', component, children: [{ path: 'dashboard', name: 'Dashboard', component }] },
    { path: '/order', redirect: '/order/list', component, children: [{ path: 'list', name: 'OrderList', component }] }
  ]
  const routes = buildH5Routes(source, layout)
  assert.ok(routes.some(route => route.path === '/h5' && route.redirect === '/h5/dashboard'))
  assert.ok(routes.some(route => route.path === '/h5/order' && route.redirect === '/h5/order/list'))
  assert.ok(routes.some(route => route.children?.some(child => child.name === 'H5OrderList')))
  assert.equal(routes.some(route => route.path === '/h5/login'), false)
})

test('canonical links inside an H5 session stay in the H5 shell', () => {
  const target = h5RedirectTarget(
    { path: '/order/list', query: { status: 'PAID' }, hash: '#top' },
    { path: '/h5/dashboard' }
  )
  assert.equal(target.path, '/h5/order/list')
  assert.deepEqual(target.query, { status: 'PAID' })
  assert.equal(target.hash, '#top')
  assert.equal(h5RedirectTarget({ path: '/h5/order/list' }, { path: '/h5/dashboard' }), null)
  assert.equal(h5RedirectTarget({ path: '/order/list' }, { path: '/dashboard' }), null)
})

test('admin no longer switches routes from viewport width', () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(app, /switchAdminViewport/)
  assert.doesNotMatch(app, /MOBILE_ADMIN_MAX_WIDTH/)
  assert.match(app, /<router-view \/>/)
})
