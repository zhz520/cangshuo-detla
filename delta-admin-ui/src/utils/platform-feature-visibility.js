import { ref } from 'vue'
import { getPlatformFeatures } from '@/api/platform'
import { getToken, getUserInfo } from './auth'
import { can, loadStaffPermissions } from './staff-permissions'

export const enabledPlatformFeatures = ref(null)
export const platformFeatureError = ref('')
let pending = null, pendingToken = '', cachedToken = '', loadedAt = 0

export function platformFeatureAllowed(id) {
  return !id || enabledPlatformFeatures.value?.has(id) === true
}

export async function loadPlatformFeatureVisibility(force = false) {
  const token = getToken() || ''
  if (cachedToken !== token) { enabledPlatformFeatures.value = null; loadedAt = 0; cachedToken = token; platformFeatureError.value = '' }
  if (!token || getUserInfo()?.role !== 'admin') { enabledPlatformFeatures.value = new Set(); return enabledPlatformFeatures.value }
  if (pending && pendingToken === token) return pending
  if (!force && loadedAt && Date.now() - loadedAt < 30000) return enabledPlatformFeatures.value
  pendingToken = token
  platformFeatureError.value = ''
  pending = getPlatformFeatures({ silent: true })
    .then(response => {
      if ((getToken() || '') !== token) return loadPlatformFeatureVisibility()
      enabledPlatformFeatures.value = new Set((response.data || []).filter(item => item.enabled && item.entitled !== false && (!item.expiresAt || Date.parse(item.expiresAt) > Date.now())).map(item => item.id))
      loadedAt = Date.now()
      return enabledPlatformFeatures.value
    })
    .catch(() => {
      if ((getToken() || '') !== token) return enabledPlatformFeatures.value
      platformFeatureError.value = '暂时无法读取已启用的功能'
      enabledPlatformFeatures.value = new Set()
      loadedAt = 0
      return enabledPlatformFeatures.value
    })
    .finally(() => { if (pendingToken === token) pending = null })
  return pending
}

export async function requirePlatformFeature(feature, permission) {
  const denied = message => Object.assign(new Error(message), { code: 403, requestHandled: true })
  if (!getToken() || getUserInfo()?.role !== 'admin') throw denied('当前身份没有权限使用此功能')
  await loadStaffPermissions()
  if (!can(permission)) throw denied('当前角色没有此操作权限')
  await loadPlatformFeatureVisibility()
  if (platformFeatureError.value) throw denied(platformFeatureError.value)
  if (!platformFeatureAllowed(feature)) throw denied('此功能尚未获得有效授权或未启用')
}
