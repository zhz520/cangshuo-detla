import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('marketing routes expose coupons and invite operations to administrators', async () => {
  const router = await read('src/router/index.js')
  const navigation = await read('src/utils/navigation.js')
  assert.match(router, /MarketingCoupons/)
  assert.match(router, /MarketingInvites/)
  assert.match(navigation, /\/marketing\/coupons/)
  assert.match(navigation, /\/marketing\/invites/)
})

test('coupon editor exposes per-user multi-claim and both operating switches are documented', async () => {
  const page = await read('src/views/marketing/Coupons.vue')
  assert.match(page, /perUserLimit/)
  assert.match(page, /系统管理 → 系统配置 → 营销配置/)
  assert.match(page, /发放与核销总开关/)
})

test('invite operations surface refund reversals and outstanding debts', async () => {
  const page = await read('src/views/marketing/Invites.vue')
  assert.match(page, /待追回台账/)
  assert.match(page, /已退款冲正/)
  assert.match(page, /invite_reward|debts/)
})
