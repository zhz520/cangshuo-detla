export function installClientPlatform() {
  const ua = navigator.userAgent || ''
  const lower = ua.toLowerCase()
  const root = document.documentElement
  const client = /micromessenger/.test(lower) ? 'wechat'
    : /(?:mqqbrowser|qq\/)/.test(lower) ? 'qq'
    : 'browser'
  const os = /iphone|ipad|ipod/.test(lower) ? 'ios'
    : /android/.test(lower) ? 'android'
    : /macintosh/.test(lower) && navigator.maxTouchPoints > 1 ? 'ios'
    : 'desktop'
  root.dataset.client = client
  root.dataset.os = os
  root.dataset.mobile = /mobile|iphone|ipad|ipod|android/.test(lower) ? 'true' : 'false'
}
