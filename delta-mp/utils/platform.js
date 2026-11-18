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

export function isMobileUserAgent(userAgent = '', maxTouchPoints = 0, platform = '') {
  return /Android|iPhone|iPad|iPod|Mobile|HarmonyOS|OpenHarmony|Windows Phone|IEMobile/i.test(userAgent) ||
    (/Macintosh/i.test(userAgent) && /Mac|iPhone|iPad/i.test(platform) && Number(maxTouchPoints) > 1)
}

export function isMobileBrowser() {
  if (!isH5() || typeof navigator === 'undefined') return false
  return isMobileUserAgent(navigator.userAgent, navigator.maxTouchPoints, navigator.platform)
}

/**
 * 按浏览器场景和后台开关选择可实际完成的微信支付通道。
 * P0 支付约定：公众号支付开启时，仅手机微信直接 JSAPI，其他浏览器均扫码。
 * 未开启公众号支付时，保留原 H5 支付及小程序独立通道。
 */
export function wechatPayChannel({ miniappEnabled = true, h5Enabled = false, officialEnabled = false } = {}) {
  return resolveWechatPayChannel({
    miniapp: isMpWeixin(),
    web: isH5(),
    inWechat: isWechatBrowser(),
    mobile: !isDesktopWechatUserAgent(typeof navigator === 'undefined' ? '' : navigator.userAgent) && isMobileBrowser(),
    miniappEnabled,
    h5Enabled,
    officialEnabled
  })
}

export function resolveWechatPayChannel({ miniapp, web, inWechat, mobile, miniappEnabled, h5Enabled, officialEnabled }) {
  if (miniapp) return miniappEnabled ? 'MINIAPP' : null
  if (!web) return null
  if (officialEnabled) return inWechat && mobile ? 'OFFICIAL' : 'OFFICIAL_QR'
  if (inWechat) {
    return h5Enabled ? 'OPEN_IN_BROWSER' : null
  }
  if (mobile && h5Enabled) return 'H5'
  return h5Enabled ? 'OPEN_IN_MOBILE_BROWSER' : null
}

export function isDesktopWechatUserAgent(userAgent = '') {
  return /WindowsWechat|MacWechat|WeChatWin|WeChatMac|Windows NT|X11/i.test(userAgent)
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

/** Append the business return page to a WeChat H5 payment URL. */
export function withH5PaymentReturnUrl(h5Url, returnUrl) {
  try {
    const target = new URL(String(h5Url))
    const redirect = new URL(String(returnUrl))
    if (target.protocol !== 'https:' || redirect.protocol !== 'https:') return ''
    target.searchParams.set('redirect_url', redirect.toString())
    return target.toString()
  } catch (_) {
    return ''
  }
}
