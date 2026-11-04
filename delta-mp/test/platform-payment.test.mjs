import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { isMobileUserAgent, isWechatUserAgent, resolveWechatPayChannel } from '../utils/platform.js'

test('each configured WeChat payment method is routed to a compatible browser scene', () => {
  const base = { miniappEnabled: true, h5Enabled: true, officialEnabled: false }
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: true, web: false }), 'MINIAPP')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: true, officialEnabled: true }), 'OFFICIAL')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: false, officialEnabled: true }), 'OFFICIAL_QR')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: true }), 'H5')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: true, officialEnabled: true }), 'H5')
  assert.equal(resolveWechatPayChannel({ ...base, h5Enabled: false, miniapp: false, web: true, inWechat: false, mobile: true, officialEnabled: true }), 'OFFICIAL_QR')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: false, officialEnabled: true }), 'OFFICIAL_QR')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: false, officialEnabled: false }), 'OPEN_IN_MOBILE_BROWSER')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: true, h5Enabled: true, officialEnabled: false }), 'OPEN_IN_BROWSER')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: false, h5Enabled: true, officialEnabled: false }), 'OPEN_IN_BROWSER')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: false, h5Enabled: false, officialEnabled: false }), null)
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: false, h5Enabled: false, officialEnabled: false }), null)
  assert.equal(resolveWechatPayChannel({ ...base, miniappEnabled: false, miniapp: true, web: false }), null)
})

test('desktop and mobile WeChat user agents are distinguished without misclassifying tablets', () => {
  const windowsWechat = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/81.0 Safari/537.36 MicroMessenger/7.0.20 WindowsWechat XWEB/8391'
  const macWechat = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 MicroMessenger/7.0.20 MacWechat'
  const androidWechat = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36 MicroMessenger/8.0.50'
  const iphoneWechat = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 MicroMessenger/8.0.50'
  const ipadDesktopMode = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15'
  for (const ua of [windowsWechat, macWechat, androidWechat, iphoneWechat]) assert.equal(isWechatUserAgent(ua), true)
  assert.equal(isWechatUserAgent('Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 Safari/537.36'), false)
  assert.equal(isMobileUserAgent(windowsWechat), false)
  assert.equal(isMobileUserAgent(macWechat), false)
  assert.equal(isMobileUserAgent(androidWechat), true)
  assert.equal(isMobileUserAgent(iphoneWechat), true)
  assert.equal(isMobileUserAgent(ipadDesktopMode, 5), true)
  assert.equal(isMobileUserAgent('Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 Safari/537.36', 0), false)
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
    assert.match(source, /channel === 'OFFICIAL_QR'[\s\S]*channel === 'OFFICIAL'/)
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
