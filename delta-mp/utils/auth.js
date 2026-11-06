import { clientStorage } from '@/utils/client-storage'
/**
 * Token 存取管理
 * - user_token：用户端专用
 * - player_token：接单员端专用，从用户端切换到接单员端时调用 switch-to-player 获取并保存
 * - cs_token：仅客服端
 * 每次切换到接单员端都需重新获取 player_token
 */

const USER_TOKEN_KEY = 'user_token'
const PLAYER_TOKEN_KEY = 'player_token'
const CS_TOKEN_KEY = 'cs_token'
const GUEST_TOKEN_KEY = 'guest_token'
const GUEST_INFO_KEY = 'guest_info'

// ========== 用户 Token ==========
export function getUserToken() {
  return clientStorage.get(USER_TOKEN_KEY) || ''
}

export function setUserToken(token) {
  clientStorage.set(USER_TOKEN_KEY, token)
}

export function removeUserToken() {
  clientStorage.remove(USER_TOKEN_KEY)
}

// ========== 接单员 Token（切换接单员端时获取并保存） ==========
export function getPlayerToken() {
  return clientStorage.get(PLAYER_TOKEN_KEY) || ''
}

export function setPlayerToken(token) {
  clientStorage.set(PLAYER_TOKEN_KEY, token)
}

export function removePlayerToken() {
  clientStorage.remove(PLAYER_TOKEN_KEY)
}

// ========== 游客影子会话 ==========
export function getGuestToken() {
  return clientStorage.get(GUEST_TOKEN_KEY) || ''
}

export function getGuestInfo() {
  const info = clientStorage.get(GUEST_INFO_KEY)
  return info && typeof info === 'object' ? info : null
}

export function setGuestSession(data) {
  if (!data?.token) throw new Error('游客会话凭据无效')
  clientStorage.set(GUEST_TOKEN_KEY, data.token)
  clientStorage.set(GUEST_INFO_KEY, {
    guestUserId: data.guestUserId,
    nickname: data.nickname || '游客',
    maskedPhone: data.maskedPhone || '',
    orderId: data.orderId || null
  })
}

export function removeGuestSession() {
  clientStorage.remove(GUEST_TOKEN_KEY)
  clientStorage.remove(GUEST_INFO_KEY)
}

// ========== 客服Token ==========
const CS_INFO_KEY = 'cs_info'

export function getCsToken() {
  return clientStorage.get(CS_TOKEN_KEY) || ''
}

export function setCsToken(token) {
  clientStorage.set(CS_TOKEN_KEY, token)
}

export function getCsInfo() {
  return clientStorage.get(CS_INFO_KEY) || null
}

export function setCsInfo(info) {
  clientStorage.set(CS_INFO_KEY, info)
}

export function removeCsToken() {
  clientStorage.remove(CS_TOKEN_KEY)
  clientStorage.remove(CS_INFO_KEY)
}

// ========== 按角色取 token ==========
export function getTokenByRole(role) {
  if (role === 'cs') return getCsToken()
  if (role === 'player') return getPlayerToken()
  if (role === 'guest') return getGuestToken()
  return getUserToken()
}

export function getCurrentRole() {
  const requested = clientStorage.get('app_role') || 'user'
  if (requested === 'player' && getPlayerToken()) return 'player'
  if (requested === 'cs' && getCsToken()) return 'cs'
  if (getUserToken()) return 'user'
  if (getGuestToken()) return 'guest'
  return requested
}

// ========== 清除全部token ==========
export function clearAllTokens() {
  removeUserToken()
  removePlayerToken()
  removeCsToken()
  removeGuestSession()
}
