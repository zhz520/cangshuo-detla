import { createCsSession, sendChatMessage } from '@/api/chat'
import { useSiteStore } from '@/store/site'
import { useUserStore } from '@/store/user'

function openExternal(url) {
  // #ifdef H5
  window.location.assign(url)
  return
  // #endif
  uni.setClipboardData({
    data: url,
    success: () => uni.showToast({ title: '客服链接已复制，请在浏览器打开', icon: 'none' })
  })
}

export async function openCustomerService(product = null) {
  const siteStore = useSiteStore()
  await siteStore.fetchSiteConfig()
  const external = siteStore.customerServiceMode === 'external' && /^https:\/\//i.test(siteStore.customerServiceUrl)
  if (external) {
    openExternal(siteStore.customerServiceUrl)
    return
  }
  const userStore = useUserStore()
  if (!userStore.checkLogin()) return
  const { data: session } = await createCsSession()
  if (product) {
    const content = JSON.stringify({
      id: product.id,
      name: product.name,
      price: product.price,
      coverImage: product.coverImage || product.image
    })
    await sendChatMessage({ sessionId: session.id, type: 'PRODUCT', content })
  }
  uni.navigateTo({ url: '/pages/chat/room?sessionId=' + session.id + '&name=' + encodeURIComponent('在线客服') })
}
