import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { isDesktopWechatUserAgent, isMobileUserAgent, isWechatUserAgent, resolveWechatPayChannel, withH5PaymentReturnUrl } from '../utils/platform.js'
import { createPaymentQr } from '../utils/payment-qr.js'

test('each configured WeChat payment method is routed to a compatible browser scene', () => {
  const base = { miniappEnabled: true, h5Enabled: true, officialEnabled: false }
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: true, web: false }), 'MINIAPP')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: true, officialEnabled: true }), 'OFFICIAL')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: false, officialEnabled: true }), 'OFFICIAL_QR')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: true }), 'H5')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false, mobile: true, officialEnabled: true }), 'OFFICIAL_QR')
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
  assert.equal(isMobileUserAgent(ipadDesktopMode, 5, 'MacIntel'), true)
  assert.equal(isMobileUserAgent(macWechat, 10, 'Win32'), false)
  assert.equal(isDesktopWechatUserAgent(macWechat), true)
  assert.equal(isDesktopWechatUserAgent(windowsWechat + ' Mobile'), true)
  assert.equal(isDesktopWechatUserAgent(iphoneWechat), false)
  assert.equal(isMobileUserAgent('Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 Safari/537.36', 0), false)
})

test('checkout renders a PNG for a long official OAuth URL independently of the current theme', async () => {
  const url = 'https://open.weixin.qq.com/connect/oauth2/authorize?appid=wx-example&redirect_uri=' +
    encodeURIComponent('https://shop.example/api/pay/wx/official/callback') +
    '&response_type=code&scope=snsapi_base&state=' + 'a'.repeat(64) + '#wechat_redirect'
  const png = await createPaymentQr(url)
  assert.match(png, /^data:image\/png;base64,iVBORw0KGgo/)
  const data = Buffer.from(png.split(',')[1], 'base64')
  assert.equal(data.readUInt32BE(16), 560)
  assert.equal(data.readUInt32BE(20), 560)
})

test('H5 payment is opt-in when the H5 site may not be deployed', async () => {
  const platform = await readFile(new URL('../utils/platform.js', import.meta.url), 'utf8')
  const store = await readFile(new URL('../store/site.js', import.meta.url), 'utf8')
  const order = await readFile(new URL('../pages/order/pay.vue', import.meta.url), 'utf8')
  const recharge = await readFile(new URL('../pages/wallet/recharge.vue', import.meta.url), 'utf8')
  assert.match(platform, /h5Enabled = false/)
  assert.match(store, /wxPayH5Enabled = ref\(false\)/)
  assert.match(order, /h5Enabled: siteRes\.data\?\.wx_pay_h5_enabled === 'true'/)
  assert.match(order, /h5Enabled: site\.wx_pay_h5_enabled === 'true'/)
  assert.match(recharge, /h5Enabled: site\.wx_pay_h5_enabled === 'true'/)
})

test('H5 payment URLs preserve the encoded business return page', () => {
  const result = withH5PaymentReturnUrl('https://wxpay.example/pay?prepay_id=abc',
    'https://shop.example/#/pages/order/detail?id=123')
  const parsed = new URL(result)
  assert.equal(parsed.searchParams.get('prepay_id'), 'abc')
  assert.equal(parsed.searchParams.get('redirect_url'), 'https://shop.example/#/pages/order/detail?id=123')
  assert.equal(withH5PaymentReturnUrl('http://unsafe.example/pay', 'https://shop.example/#/pages/wallet/index'), '')
  assert.equal(withH5PaymentReturnUrl('not-a-url', 'https://shop.example/#/pages/wallet/index'), '')
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
