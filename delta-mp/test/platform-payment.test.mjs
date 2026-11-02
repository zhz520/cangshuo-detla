import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolveWechatPayChannel } from '../utils/platform.js'

test('each configured WeChat payment method is selected independently without browser sniffing', () => {
  const base = { miniappEnabled: true, h5Enabled: true, officialEnabled: false }
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: true }), 'MINIAPP')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false }), 'H5')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, officialEnabled: true }), 'OFFICIAL')
  assert.equal(resolveWechatPayChannel({ ...base, miniappEnabled: false, miniapp: true }), null)
  assert.equal(resolveWechatPayChannel({ ...base, h5Enabled: false, miniapp: false }), null)
  assert.equal(resolveWechatPayChannel({ ...base, h5Enabled: false, miniapp: false, officialEnabled: true }), 'OFFICIAL')
})

test('payment pages do not filter configured methods by WeChat browser', async () => {
  const paths = [
    '../pages/order/pay.vue',
    '../pages/wallet/recharge.vue',
    '../pages-player/apply/index.vue',
    '../pages-player/withdraw/index.vue'
  ]
  for (const path of paths) {
    const source = await readFile(new URL(path, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /isWechatBrowser|canStartH5WechatPayment/)
  }
})
