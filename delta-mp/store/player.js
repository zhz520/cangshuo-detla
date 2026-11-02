import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getPlayerProfile } from '@/api/player'
import { getTokenByRole } from '@/utils/auth'

export const usePlayerStore = defineStore('player', () => {
  const playerInfo = ref(null)
  const pending = new Map()
  let generation = 0
  let lastKey = '', fetchedAt = 0

  const playerId = computed(() => playerInfo.value?.id || null)
  const auditStatus = computed(() => playerInfo.value?.status || '')
  const isApproved = computed(() => playerInfo.value?.status === 'ACTIVE')

  /** 获取接单员资料；opts.role='user' 时用用户token（切换前校验），'player' 用接单员token */
  async function fetchProfile(opts = {}) {
    const role = opts.role || 'player', token = getTokenByRole(role)
    const key = role + ':' + token
    if (pending.has(key)) return pending.get(key)
    if (!opts.force && playerInfo.value && lastKey === key && Date.now() - fetchedAt < 3000) return playerInfo.value
    const started = generation
    const task = load()
    pending.set(key, task)
    return task
    async function load() {
    try {
      const { data } = await getPlayerProfile({ loading: false, ...opts })
      if (started === generation && token === getTokenByRole(role)) { playerInfo.value = data; lastKey = key; fetchedAt = Date.now() }
      return data
    } catch (e) {
      return null
    } finally { if (pending.get(key) === task) pending.delete(key) }
    }
  }

  /** 清空接单员信息 */
  function reset() {
    generation++
    lastKey = ''; fetchedAt = 0
    pending.clear()
    playerInfo.value = null
  }

  return {
    playerInfo,
    playerId,
    auditStatus,
    isApproved,
    fetchProfile,
    reset
  }
})
