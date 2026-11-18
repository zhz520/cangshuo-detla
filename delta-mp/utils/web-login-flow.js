import { clientStorage } from '@/utils/client-storage'
import { useAppStore } from '@/store/app'
import { useChatStore } from '@/store/chat'

export const home = '/pages/index/index'
export function safeLoginReturn(value) {
  if (typeof value !== 'string') return home
  let target = value
  if (!target.startsWith('/')) {
    try { target = decodeURIComponent(target) } catch (_) { return home }
  }
  if (target.length > 600 || !/^\/(pages|pages-player)\/[\w/-]+(?:\?[^#\\\r\n]*)?$/.test(target)
    || target.startsWith('/pages/login/') || target.startsWith('/pages-cs/')) return home
  return target
}

export function removeWechatTicketFromUrl() {
  // #ifdef H5
  const url = new URL(window.location.href)
  const split = url.hash.indexOf('?')
  if (split < 0) return
  const params = new URLSearchParams(url.hash.slice(split + 1))
  if (!params.has('wechat_ticket')) return
  params.delete('wechat_ticket')
  const query = params.toString()
  url.hash = url.hash.slice(0, split) + (query ? '?' + query : '')
  window.history.replaceState(window.history.state, '', url)
  // #endif
}

export function enterAfterLogin(target) {
  clientStorage.remove('invite_code')
  clientStorage.set('app_role', 'user')
  useAppStore().restoreRole()
  const chat = useChatStore()
  chat.disconnect()
  chat.connect()
  uni.reLaunch({ url: safeLoginReturn(target) })
}
