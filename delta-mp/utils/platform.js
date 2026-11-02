export function isH5() {
  let result = false
  // #ifdef H5
  result = true
  // #endif
  return result
}

export function isMpWeixin() {
  let result = false
  // #ifdef MP-WEIXIN
  result = true
  // #endif
  return result
}

export function isWechatBrowser() {
  return isH5() && typeof navigator !== 'undefined' && /MicroMessenger/i.test(navigator.userAgent)
}

export function isMobileBrowser() {
  if (!isH5() || typeof navigator === 'undefined') return false
  return /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent) ||
    (/Macintosh/i.test(navigator.userAgent) && navigator.maxTouchPoints > 1)
}

/** H5 payment is only supported by WeChat in a mobile browser outside WeChat. */
export function canStartH5WechatPayment() {
  if (!isH5()) return false
  let message = ''
  if (isWechatBrowser()) {
    message = '请点击右上角“…”选择“在浏览器中打开”，再使用微信支付。订单也可选择余额支付。'
  } else if (!isMobileBrowser()) {
    message = '请用手机浏览器打开本站使用微信支付。电脑端订单可选择余额支付。'
  }
  if (!message) return true
  uni.showModal({ title: '支付提示', content: message, showCancel: false, confirmText: '我知道了' })
  return false
}

/**
 * 选择当前环境可用的微信支付通道。
 * 小程序使用小程序 JSAPI；微信内置浏览器使用公众号 JSAPI；普通手机浏览器使用 H5 支付。
 */
export function wechatPayChannel({ miniappEnabled = true, h5Enabled = true, officialEnabled = false } = {}) {
  return resolveWechatPayChannel({
    miniapp: isMpWeixin(),
    web: isH5(),
    inWechat: isWechatBrowser(),
    mobile: isMobileBrowser(),
    miniappEnabled,
    h5Enabled,
    officialEnabled
  })
}

export function resolveWechatPayChannel({ miniapp, web, inWechat, mobile, miniappEnabled, h5Enabled, officialEnabled }) {
  if (miniapp) return miniappEnabled ? 'MINIAPP' : null
  if (!web) return null
  if (inWechat) return officialEnabled && mobile ? 'OFFICIAL' : null
  return h5Enabled ? 'H5' : null
}
