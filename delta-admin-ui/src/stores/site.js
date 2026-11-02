import { defineStore } from 'pinia'
import { ref } from 'vue'
import request from '@/utils/request'

export const useSiteStore = defineStore('site', () => {
  const siteName = ref('护航代练')
  const subtitle = ref('专业游戏代练平台')
  const logo = ref('')
  const adminTitle = ref('护航管理后台')
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref('')
  let pending = null

  function fetchSiteConfig(force = false) {
    if (pending) return pending
    if (loaded.value && !force) return Promise.resolve()
    loading.value = true
    error.value = ''
    pending = request({ url: '/system/config/site', method: 'get', silent: true }).then(res => {
      const data = res.data || {}
      if (data.site_name) siteName.value = data.site_name
      if (data.site_subtitle) subtitle.value = data.site_subtitle
      if (data.site_logo) logo.value = data.site_logo
      if (data.site_admin_title) adminTitle.value = data.site_admin_title
      loaded.value = true
    }).catch(() => {
      error.value = '站点信息暂时无法加载'
    }).finally(() => { loading.value = false; pending = null })
    return pending
  }

  return { siteName, subtitle, logo, adminTitle, loaded, loading, error, fetchSiteConfig }
})
