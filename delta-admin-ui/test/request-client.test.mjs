import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequestClient } from '../src/utils/request-client.js'
import { paidRequestAccess } from '../src/utils/feature-access-policy.js'

function fixture() {
  const calls = []
  let token = 'first', active = 0
  const client = createRequestClient(config => new Promise((resolve, reject) => calls.push({ config, resolve, reject })),
    config => JSON.stringify([token, config.url, config.params]), { start: () => active++, finish: () => active-- })
  return { client, calls, active: () => active, changeToken: () => { token = 'second' } }
}

test('concurrent identical reads share the result and loading lifecycle, completed reads are fresh', async () => {
  const f = fixture(), first = f.client.get('/orders', { params: { page: 1 } }), second = f.client({ url: '/orders', params: { page: 1 } })
  assert.equal(first, second)
  assert.equal(f.calls.length, 1)
  assert.equal(f.active(), 1)
  f.calls[0].resolve({ records: [1] })
  assert.deepEqual(await first, { records: [1] })
  assert.equal(f.active(), 0)
  const refreshed = f.client.get('/orders', { params: { page: 1 } })
  assert.equal(f.calls.length, 2)
  f.calls[1].resolve({ records: [2] })
  assert.deepEqual(await refreshed, { records: [2] })
})

test('writes are never shared and a refresh after a write cannot reuse a pre-write read', async () => {
  const f = fixture(), before = f.client.get('/orders'), write = f.client.post('/orders', { id: 1 }), writeAgain = f.client.post('/orders', { id: 1 }), after = f.client.get('/orders')
  assert.equal(f.calls.length, 4)
  assert.notEqual(before, after)
  assert.notEqual(write, writeAgain)
  f.calls.forEach(call => call.resolve({}))
  await Promise.all([before, write, writeAgain, after])
  assert.equal(f.active(), 0)
})

test('different queries, identities, signals and explicit opt-outs remain independent', async () => {
  const f = fixture(), tasks = [f.client.get('/orders', { params: { page: 1 } }), f.client.get('/orders', { params: { page: 2 } })]
  f.changeToken()
  tasks.push(f.client.get('/orders', { params: { page: 1 } }))
  const controller = new AbortController()
  tasks.push(f.client.get('/orders', { signal: controller.signal }), f.client.get('/orders', { signal: controller.signal }), f.client.get('/orders', { dedupe: false }), f.client.get('/orders', { dedupe: false }))
  assert.equal(f.calls.length, 7)
  f.calls.forEach(call => call.resolve({}))
  await Promise.all(tasks)
})

test('failed shared reads release their key and can be retried', async () => {
  const f = fixture(), first = f.client.get('/orders'), duplicate = f.client.get('/orders')
  const rejected = assert.rejects(first, /offline/)
  f.calls[0].reject(new Error('offline'))
  await rejected
  assert.equal(first, duplicate)
  assert.equal(f.active(), 0)
  const retry = f.client.get('/orders')
  assert.equal(f.calls.length, 2)
  f.calls[1].resolve('recovered')
  assert.equal(await retry, 'recovered')
})

test('synchronous transport failures also clear loading and allow another attempt', async () => {
  let active = 0
  const client = createRequestClient(() => { throw new Error('transport') }, () => 'same', { start: () => active++, finish: () => active-- })
  await assert.rejects(client.get('/orders'), /transport/)
  assert.equal(active, 0)
  await assert.rejects(client.get('/orders'), /transport/)
})

test('paid request policy covers reads, details and writes without blocking public/catalog APIs', () => {
  assert.deepEqual(paidRequestAccess('/admin/platform/reports/income?startDate=2026-01-01'), { feature: 'advanced_income_reports', permission: 'finance.read' })
  assert.deepEqual(paidRequestAccess('/admin/identity/applications/7'), { feature: 'identity_verification', permission: 'user.manage' })
  assert.deepEqual(paidRequestAccess('/admin/wechat/notifications/8/retry'), { feature: 'official_wechat_notifications', permission: 'system.manage' })
  assert.deepEqual(paidRequestAccess('/admin/wechat/accounts/abc/templates/sync'), { feature: 'official_wechat_notifications', permission: 'system.manage' })
  assert.deepEqual(paidRequestAccess('/admin/email-notifications/records/8/retry'), { feature: 'email_notifications', permission: 'system.manage' })
  assert.deepEqual(paidRequestAccess('/admin/player-layout'), { feature: 'player_directory_layouts', permission: 'system.manage' })
  assert.equal(paidRequestAccess('/admin/platform/features'), null)
  assert.equal(paidRequestAccess('/system/config/site'), null)
})
