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

export function isWechatUserAgent(userAgent = '') {
  return /MicroMessenger|WindowsWechat|MacWechat/i.test(userAgent)
}

export function isWechatBrowser() {
  return isH5() && typeof navigator !== 'undefined' && isWechatUserAgent(navigator.userAgent)
}

export function isMobileUserAgent(userAgent = '', maxTouchPoints = 0) {
  return /Android|iPhone|iPad|iPod|Mobile|HarmonyOS|OpenHarmony|Windows Phone|IEMobile/i.test(userAgent) ||
    (/Macintosh/i.test(userAgent) && Number(maxTouchPoints) > 1)
}

export function isMobileBrowser() {
  if (!isH5() || typeof navigator === 'undefined') return false
  return isMobileUserAgent(navigator.userAgent, navigator.maxTouchPoints)
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
    // 电脑微信的 UA 也包含 MicroMessenger，但桌面端不能可靠调用公众号 JSAPI 收银台。
    // 改用授权地址二维码，由手机微信扫码后进入公众号支付链路。
    if (!mobile) return officialEnabled ? 'OFFICIAL_QR' : h5Enabled ? 'OPEN_IN_BROWSER' : null
    if (officialEnabled) return 'OFFICIAL'
    return h5Enabled ? 'OPEN_IN_BROWSER' : null
  }
  if (mobile && h5Enabled) return 'H5'
  if (officialEnabled) return 'OFFICIAL_QR'
  return h5Enabled ? 'OPEN_IN_MOBILE_BROWSER' : null
}

export function showWechatPaymentEnvironmentPrompt(channel) {
  const content = channel === 'OPEN_IN_BROWSER'
      ? '微信内暂不能使用当前支付方式，请复制本页链接并在手机浏览器中打开后重试。'
      : channel === 'OPEN_IN_MOBILE_BROWSER'
        ? '当前浏览器无法直接完成微信支付，请复制本页链接并在手机浏览器中打开后重试。'
      : ''
  if (!content) return false
  uni.showModal({ title: '微信支付', content, showCancel: false, confirmText: '知道了' })
  return true
}
