import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getSiteConfig, getThemeConfig } from '@/api/config'
import { clientStorage as themeStorage } from '@/utils/client-storage'

export const useSiteStore = defineStore('site', () => {
  const siteName = ref('三角洲')
  const subtitle = ref('专业游戏平台')
  const logo = ref('')
  const adminTitle = ref('三角洲管理后台')
  /** 接单员端抽佣比例（百分比，如 10 表示 10%），由接口 /system/config/site 返回 player_commission_rate */
  const playerCommissionRate = ref(0)
  /** 打手入驻押金开关 */
  const depositRequired = ref(true)
  const depositMode = ref('REQUIRED')
  /** 后台微信支付总开关 */
  const wxPayEnabled = ref(false)
  const wxPayMiniappEnabled = ref(true)
  // H5 支付只会在明确启用后使用，不能把“未返回配置”当成 H5 已部署。
  const wxPayH5Enabled = ref(false)
  const wxPayOfficialEnabled = ref(false)
  /** 前台立即购买/立即点单总开关。 */
  const orderPurchaseEnabled = ref(false)
  /** 未登录游客下单开关。 */
  const guestCheckoutEnabled = ref(false)
  /** 用户端打手页面展示开关。 */
  const playerDirectoryEnabled = ref(true)
  /** 公告是否在浏览器刷新后再次展示 */
  const noticePopupEveryVisit = ref(false)
  const techSupportText = ref('KK云网络提供技术支持')
  const techSupportUrl = ref('')
  const customerServiceMode = ref('internal')
  const customerServiceUrl = ref('')
  const customerServiceHomeIconVisible = ref(true)
  /** 管理员与客服后台站点，客户 H5 只负责把旧工作人员入口安全转交过去。 */
  const staffWebUrl = ref('')
  const customerWebUrl = ref('')
  const tenantThemeId = ref('default')
  const userTheme = ref('')
  const userThemeAllowed = ref(false)
  const userThemeOptions = ref([])
  let pendingTheme
  const themeId = computed(() => (userThemeAllowed.value && userTheme.value) || tenantThemeId.value)
  const externalCustomerServiceEnabled = computed(() =>
    customerServiceMode.value === 'external' && /^https:\/\//i.test(customerServiceUrl.value)
  )
  let pendingConfigRequest = null
  let noticePopupShownThisSession = false

  /** 完整平台名称（用于协议等文案） */
  const fullName = computed(() => siteName.value + '平台')

  async function fetchSiteConfig() {
    if (pendingConfigRequest) return pendingConfigRequest
    pendingConfigRequest = (async () => {
      try {
        const res = await getSiteConfig()
        const data = res.data || {}
        if (data.site_name) siteName.value = data.site_name
        if (data.site_subtitle) subtitle.value = data.site_subtitle
        if (data.site_logo) logo.value = data.site_logo
        if (data.site_admin_title) adminTitle.value = data.site_admin_title
        if (data.player_commission_rate !== undefined && data.player_commission_rate !== null) {
          playerCommissionRate.value = Number(data.player_commission_rate) || 0
        }
        if (data.player_deposit_required !== undefined) {
          depositRequired.value = data.player_deposit_required === 'true'
        }
        if (data.player_deposit_mode !== undefined) {
          depositMode.value = ['REQUIRED','NONE','BEFORE_WITHDRAW'].includes(data.player_deposit_mode) ? data.player_deposit_mode : 'REQUIRED'
        }
        if (data.wx_pay_enabled !== undefined) {
          wxPayEnabled.value = data.wx_pay_enabled === 'true'
        }
        if (data.wx_pay_miniapp_enabled !== undefined) {
          wxPayMiniappEnabled.value = data.wx_pay_miniapp_enabled === 'true'
        }
        if (data.wx_pay_h5_enabled !== undefined) {
          wxPayH5Enabled.value = data.wx_pay_h5_enabled === 'true'
        }
        if (data.wx_pay_official_enabled !== undefined) {
          wxPayOfficialEnabled.value = data.wx_pay_official_enabled === 'true'
        }
        if (data.order_purchase_enabled !== undefined) {
          orderPurchaseEnabled.value = data.order_purchase_enabled === 'true'
        }
        if (data.order_guest_checkout_enabled !== undefined) {
          guestCheckoutEnabled.value = data.order_guest_checkout_enabled === 'true'
        }
        if (data.player_directory_enabled !== undefined) {
          playerDirectoryEnabled.value = data.player_directory_enabled !== 'false'
        }
        if (data.notice_popup_every_visit !== undefined) {
          noticePopupEveryVisit.value = data.notice_popup_every_visit === 'true'
        }
        if (data.tech_support_text !== undefined) {
          techSupportText.value = String(data.tech_support_text || '').trim() || 'KK云网络提供技术支持'
        }
        if (data.tech_support_url !== undefined) {
          techSupportUrl.value = String(data.tech_support_url || '').trim()
        }
        if (data.customer_service_mode !== undefined) {
          customerServiceMode.value = data.customer_service_mode === 'external' ? 'external' : 'internal'
        }
        if (data.customer_service_external_url !== undefined) {
          customerServiceUrl.value = String(data.customer_service_external_url || '').trim()
        }
        if (data.customer_service_home_icon_visible !== undefined) {
          customerServiceHomeIconVisible.value = data.customer_service_home_icon_visible !== 'false'
        }
        if (data.staff_web_url !== undefined) {
          staffWebUrl.value = String(data.staff_web_url || '').trim()
        }
        if (data.customer_web_url !== undefined) {
          customerWebUrl.value = String(data.customer_web_url || '').trim()
        }
      } catch (e) {
        console.warn('[SiteStore] fetch site config failed', e)
      } finally {
        pendingConfigRequest = null
      }
    })()
    return pendingConfigRequest
  }

  function fetchThemeConfig() {
    if (pendingTheme) return pendingTheme
    pendingTheme = (async () => {
      try {
        const { data } = await getThemeConfig()
        tenantThemeId.value = ['default','neumorphism','dark'].includes(data?.id) ? data.id : 'default'
        const allowed = data?.userSwitchEnabled === 'true'
        userThemeAllowed.value = allowed
        userThemeOptions.value = allowed ? String(data?.userThemeOptions || 'default,dark').split(',').filter(id => ['default','dark'].includes(id)) : []
        const cached = allowed ? themeStorage.get('h5-theme-preference') : ''
        userTheme.value = userThemeOptions.value.includes(cached) ? cached : ''
        if (!allowed) themeStorage.remove('h5-theme-preference')
      } catch (_) { /* Keep the last working theme if config is temporarily unavailable. */ }
      return themeId.value
    })().finally(() => { pendingTheme = null })
    return pendingTheme
  }

  function setUserTheme(id) {
    if (!userThemeAllowed.value || (id !== '' && !userThemeOptions.value.includes(id))) return
    userTheme.value = id
    try { themeStorage.set('h5-theme-preference', id) } catch (_) {}
  }

  function hasShownNoticePopupThisSession() {
    return noticePopupShownThisSession
  }

  function markNoticePopupShownThisSession() {
    noticePopupShownThisSession = true
  }

  return { siteName, subtitle, logo, adminTitle, playerCommissionRate, depositRequired, depositMode, wxPayEnabled, wxPayMiniappEnabled, wxPayH5Enabled, wxPayOfficialEnabled, orderPurchaseEnabled, guestCheckoutEnabled, playerDirectoryEnabled, noticePopupEveryVisit, techSupportText, techSupportUrl, customerServiceMode, customerServiceUrl, customerServiceHomeIconVisible, staffWebUrl, customerWebUrl, themeId, userTheme, userThemeAllowed, userThemeOptions, setUserTheme, externalCustomerServiceEnabled, fullName, fetchSiteConfig, fetchThemeConfig, hasShownNoticePopupThisSession, markNoticePopupShownThisSession }
})
