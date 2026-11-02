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

/**
 * 按浏览器场景和后台开关选择可实际完成的微信支付通道。
 * 微信内置浏览器使用公众号 JSAPI；微信外手机浏览器使用 H5；电脑端需要引导到微信完成。
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
  if (inWechat) {
    if (officialEnabled) return 'OFFICIAL'
    return h5Enabled ? 'OPEN_IN_BROWSER' : null
  }
  if (mobile && h5Enabled) return 'H5'
  return officialEnabled ? 'OPEN_IN_WECHAT' : null
}

export function showWechatPaymentEnvironmentPrompt(channel) {
  const content = channel === 'OPEN_IN_WECHAT'
    ? '当前浏览器无法直接完成微信支付，请复制本页链接并在微信客户端中打开后重试。'
    : channel === 'OPEN_IN_BROWSER'
      ? '微信内暂不能使用当前支付方式，请复制本页链接并在手机浏览器中打开后重试。'
      : ''
  if (!content) return false
  uni.showModal({ title: '微信支付', content, showCancel: false, confirmText: '知道了' })
  return true
}
