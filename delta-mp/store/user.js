import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getUserToken, getPlayerToken, getGuestToken, getGuestInfo, setGuestSession as saveGuestSession, removeGuestSession as clearGuestStorage, setUserToken, removeUserToken, removePlayerToken } from '@/utils/auth'
import { clientStorage } from '@/utils/client-storage'
import { userLogin, h5Login, userPasswordLogin, exchangeWechatTicket, revokeSession } from '@/api/auth'
import { getUserProfile } from '@/api/user'
import { useChatStore } from '@/store/chat'

export const useUserStore = defineStore('user', () => {
  const token = ref(getUserToken())
  const userInfo = ref(null)
  // Binding/confirmation tickets live only for this H5 tab, never in the bearer-token store.
  const key = 'delta.wechat-phone-binding'
  function resume() {
    // #ifdef H5
    try {
      const saved = JSON.parse(sessionStorage.getItem(key) || 'null')
      if (saved?.until > Date.now()) return saved
      sessionStorage.removeItem(key)
    } catch (_) {}
    // #endif
    return null
  }
  const wechatPhoneBinding = ref(resume())
  function setBinding(value) {
    wechatPhoneBinding.value = value
    // #ifdef H5
    try {
      if (value) sessionStorage.setItem(key, JSON.stringify(value))
      else sessionStorage.removeItem(key)
    } catch (_) {}
    // #endif
  }
  function beginWechatPhoneBinding(ticket, target, { accountBinding = false, takeover = false, phone = '' } = {}) {
    setBinding({ ticket, target, accountBinding, takeover, phone, until: Date.now() + 600000 })
  }
  function confirmWechatPhoneBinding(confirmationTicket) {
    const { ticket, ...binding } = wechatPhoneBinding.value || {}
    setBinding({ ...binding, confirmationTicket, until: Date.now() + 300000 })
  }
  function clearWechatPhoneBinding() { setBinding(null) }
  const guestToken = ref(getGuestToken())
  const guestInfo = ref(getGuestInfo())

  const isLoggedIn = computed(() => !!token.value)
  const isGuest = computed(() => !token.value && !!guestToken.value)
  const nickname = computed(() => userInfo.value?.nickname || '未登录')
  const avatar = computed(() => userInfo.value?.avatar || '')
  const phone = computed(() => userInfo.value?.phone || '')

  /** 微信登录 */
  async function login(phoneCode = '') {
    try {
      const loginRes = await uni.login({ provider: 'weixin' })
      const { data } = await userLogin(loginRes.code, phoneCode)
      applyLoginResult(data)
      return { success: true, code: 200, data }
    } catch (e) {
      return {
        success: false,
        code: e?.code || 500,
        msg: e?.msg || e?.message || '登录失败'
      }
    }
  }

  /** H5 手机号验证码登录 */
  async function loginByPhone(phone, code, requestId) {
    try {
      const inviteCode = clientStorage.get('invite_code') || ''
      const { data } = await h5Login({ phone, code, requestId, privacyAccepted:true, inviteCode })
      applyLoginResult(data)
      if(inviteCode) clientStorage.remove('invite_code')
      return { success: true, code: 200, data }
    } catch (e) {
      return {
        success: false,
        code: e?.code || 500,
        msg: e?.msg || e?.message || '登录失败'
      }
    }
  }

  function applyLoginResult(data) {
    if(!data?.token) throw new Error('登录凭据无效')
    clearWechatPhoneBinding()
    const previousGuest = getGuestToken()
    token.value = data.token
    setUserToken(data.token)
    clearGuestSession()
    if (previousGuest) revokeSession(previousGuest).catch(() => {})
    userInfo.value = data
    const chatStore = useChatStore()
    chatStore.fetchMessageUnreadCount()
  }
  function setGuestSession(data) {
    saveGuestSession(data)
    guestToken.value = data.token
    guestInfo.value = getGuestInfo()
  }
  function clearGuestSession() {
    clearGuestStorage()
    guestToken.value = ''
    guestInfo.value = null
  }
  function syncSessionFromStorage() {
    token.value = getUserToken()
    guestToken.value = getGuestToken()
    guestInfo.value = getGuestInfo()
    if (!token.value) userInfo.value = null
  }
  async function exitGuest() {
    const current = getGuestToken()
    useChatStore().disconnect()
    try { if (current) await revokeSession(current) }
    finally {
      clearGuestSession()
      uni.reLaunch({ url: '/pages/index/index' })
    }
  }
  async function loginWithPassword(data) {
    const response=await userPasswordLogin(data);applyLoginResult(response.data);return response.data
  }

  async function exchangeWechatLogin(ticket) {
    try {
      const { data } = await exchangeWechatTicket(ticket)
      if (data?.bindTicket) return { success: false, bindTicket: data.bindTicket }
      applyLoginResult(data)
      return { success: true, code: 200, data }
    } catch (e) {
      return { success: false, code: e?.code || 500, msg: e?.msg || e?.message || '微信登录失败' }
    }
  }

  /** 获取用户资料 */
  async function fetchProfile(silent = false) {
    if (!token.value) return
    try {
      const { data } = await getUserProfile(silent ? { loading: false } : {})
      userInfo.value = data
    } catch (e) {
      // token失效时已由request拦截器处理
    }
  }

  /** 退出登录 */
  async function logout() {
    const tokens=[getUserToken(),getPlayerToken(),getGuestToken()].filter(Boolean)
    useChatStore().disconnect()
    try { await Promise.allSettled(tokens.map(value=>revokeSession(value))) }
    finally {
      token.value = ''
      userInfo.value = null
      removeUserToken()
      removePlayerToken()
      clearGuestSession()
      clearWechatPhoneBinding()
      uni.reLaunch({ url: '/pages/login/index' })
    }
  }

  /** 检查登录态，未登录则跳登录页 */
  function checkLogin() {
    if (!token.value) {
      uni.showModal({
        title: '需要登录',
        content: '该功能需要使用正式账号登录',
        confirmText: '去登录',
        cancelText: '暂不',
        success(result) {
          if (result.confirm) uni.navigateTo({ url: '/pages/login/index' })
        }
      })
      return false
    }
    return true
  }

  return {
    token,
    userInfo,
    guestToken,
    guestInfo,
    isLoggedIn,
    isGuest,
    nickname,
    avatar,
    phone,
    login,
    loginByPhone,
    wechatPhoneBinding,
    beginWechatPhoneBinding,
    confirmWechatPhoneBinding,
    clearWechatPhoneBinding,
    acceptWebLogin: applyLoginResult,
    loginWithPassword,
    exchangeWechatLogin,
    fetchProfile,
    setGuestSession,
    clearGuestSession,
    syncSessionFromStorage,
    exitGuest,
    logout,
    checkLogin
  }
})
