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

/**
 * 按后台开关独立选择微信支付通道。
 * H5 不再检查是否位于微信内置浏览器；公众号开启时优先使用公众号支付。
 */
export function wechatPayChannel({ miniappEnabled = true, h5Enabled = true, officialEnabled = false } = {}) {
  return resolveWechatPayChannel({
    miniapp: isMpWeixin(),
    miniappEnabled,
    h5Enabled,
    officialEnabled
  })
}

export function resolveWechatPayChannel({ miniapp, miniappEnabled, h5Enabled, officialEnabled }) {
  if (miniapp) return miniappEnabled ? 'MINIAPP' : null
  if (officialEnabled) return 'OFFICIAL'
  return h5Enabled ? 'H5' : null
}
