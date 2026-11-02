import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolveWechatPayChannel } from '../utils/platform.js'

test('each configured WeChat payment method is routed to a compatible browser scene', () => {
  const base = { miniappEnabled: true, h5Enabled: true, officialEnabled: false }
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: true, web: false }), 'MINIAPP')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: true, officialEnabled: true }), 'OFFICIAL')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: true }), 'H5')
  assert.equal(resolveWechatPayChannel({ ...base, h5Enabled: false, miniapp: false, web: true, inWechat: false, mobile: true, officialEnabled: true }), 'OPEN_IN_WECHAT')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: false, officialEnabled: true }), 'OPEN_IN_WECHAT')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: true, h5Enabled: true, officialEnabled: false }), 'OPEN_IN_BROWSER')
  assert.equal(resolveWechatPayChannel({ ...base, miniappEnabled: false, miniapp: true, web: false }), null)
})

test('payment pages share browser routing and never open WeChat OAuth outside WeChat', async () => {
  const paths = [
    '../pages/order/pay.vue',
    '../pages/wallet/recharge.vue',
    '../pages-player/apply/index.vue',
    '../pages-player/withdraw/index.vue'
  ]
  for (const path of paths) {
    const source = await readFile(new URL(path, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /isWechatBrowser|canStartH5WechatPayment/)
    assert.match(source, /showWechatPaymentEnvironmentPrompt/)
  }
})

test('customer payment surfaces use the generic WeChat Pay label', async () => {
  const paths = [
    '../pages/order/pay.vue',
    '../pages/wallet/recharge.vue',
    '../pages-player/apply/index.vue',
    '../pages-player/withdraw/index.vue'
  ]
  for (const path of paths) {
    const source = await readFile(new URL(path, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /公众号支付|微信 H5 支付|微信公众号支付|微信小程序支付/)
  }
})
