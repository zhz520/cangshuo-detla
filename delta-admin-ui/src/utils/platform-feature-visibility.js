import { ref } from 'vue'
import { getPlatformFeatures } from '@/api/platform'

export const enabledPlatformFeatures = ref(null)
let pending = null

export function platformFeatureAllowed(id) {
  return !id || enabledPlatformFeatures.value?.has(id) === true
}

export async function loadPlatformFeatureVisibility(force = false) {
  if (pending) return pending
  if (!force && enabledPlatformFeatures.value !== null) return enabledPlatformFeatures.value
  pending = getPlatformFeatures()
    .then(response => {
      enabledPlatformFeatures.value = new Set((response.data || []).filter(item => item.enabled).map(item => item.id))
      return enabledPlatformFeatures.value
    })
    .catch(() => {
      if (enabledPlatformFeatures.value === null) enabledPlatformFeatures.value = new Set()
      return enabledPlatformFeatures.value
    })
    .finally(() => { pending = null })
  return pending
}
