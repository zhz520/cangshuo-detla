import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const read = path => readFile(resolve(root, path), 'utf8')

test('customer-service routing exposes product/category rules and shift-aware groups', async () => {
  const router = await read('src/router/index.js')
  const navigation = await read('src/utils/navigation.js')
  const page = await read('src/views/chat/CsRouting.vue')
  const api = await read('src/api/cs-routing.js')
  const order = await read('src/views/order/OrderList.vue')
  assert.match(router, /path:\s*'routing'/)
  assert.match(router, /客服分配/)
  assert.match(router, /permission:\s*'system\.manage'/)
  assert.match(navigation, /\/chat\/routing/)
  assert.match(page, /客服组/)
  assert.match(page, /上班中/)
  assert.match(page, /assigneeType/)
  assert.match(page, /matchType/)
  assert.match(page, /groupWithout|客服组只从当前上班成员中选人/)
  assert.match(api, /\/admin\/cs-routing\/rules/)
  assert.match(api, /\/admin\/cs-routing\/groups/)
  assert.match(api, /\/admin\/cs-routing\/staff/)
  assert.match(order, /归属客服/)
  assert.match(order, /adminOrderReassignCs/)
  assert.match(order, /csStaff/)
})
