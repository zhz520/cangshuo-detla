import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('marketing routes expose coupons, invite operations and membership tiers to administrators', async () => {
  const router = await read('src/router/index.js')
  const navigation = await read('src/utils/navigation.js')
  assert.match(router, /MarketingCoupons/)
  assert.match(router, /MarketingInvites/)
  assert.match(router, /MarketingMembership/)
  assert.match(navigation, /\/marketing\/coupons/)
  assert.match(navigation, /\/marketing\/invites/)
  assert.match(navigation, /\/marketing\/membership/)
})

test('coupon editor exposes per-user multi-claim and both operating switches are documented', async () => {
  const page = await read('src/views/marketing/Coupons.vue')
  assert.match(page, /perUserLimit/)
  assert.match(page, /系统管理 → 系统配置 → 营销配置/)
  assert.match(page, /发放与核销总开关/)
  assert.match(page, /最近领取与核销/)
  assert.match(page, /getCouponRecords/)
  assert.match(page, /已有用户领取/)
  assert.match(page, /termsLocked/)
})

test('invite operations surface refund reversals and outstanding debts', async () => {
  const page = await read('src/views/marketing/Invites.vue')
  assert.match(page, /待追回台账/)
  assert.match(page, /已退款冲正/)
  assert.match(page, /invite_reward|debts/)
  assert.match(page, /markInviteDebtRecovered/)
})

test('membership editor validates every tier through the dedicated admin page', async () => {
  const page = await read('src/views/marketing/MembershipLevels.vue')
  const api = await read('src/api/marketing.js')
  assert.match(page, /累计净消费门槛/)
  assert.match(page, /discountRate/)
  assert.match(page, /最低等级门槛必须为 0 元/)
  assert.match(page, /等级越高折扣不能变差/)
  assert.match(api, /\/admin\/membership/)
})
