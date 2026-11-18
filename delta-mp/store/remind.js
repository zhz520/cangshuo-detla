import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getWorkbench } from '@/api/workbench'
import { getCurrentRole, getTokenByRole } from '@/utils/auth'
import { playMessageNotification } from '@/utils/notificationFeedback'

export const useRemindStore = defineStore('remind', () => {
  const summary = ref(null)
  const categories = computed(() => summary.value?.categories || [])
  const count = (kind, key = 'unread') => categories.value.find(item => item.kind === kind)?.[key] || 0
  const complaintUnread = computed(() => count('COMPLAINT'))
  const relayUnread = computed(() => count('RELAY'))
  const replaceUnread = computed(() => count('REPLACE'))
  const inviteCount = computed(() => count('INVITE', 'pending'))
  const messageUnread = computed(() => summary.value?.chatUnread || 0)
  const systemUnread = computed(() => summary.value?.systemUnread || 0)
  const workbenchUnread = computed(() => summary.value?.unread || 0)
  let timer
  let refreshTimer
  let identity = ''
  let pending
  let generation = 0
  let badgeRevision = 0
  const badgeCache = { systemUnread: 'systemUnread', chatUnread: 'chatUnread' }

  function refresh(role = getCurrentRole()) {
    const token = getTokenByRole(role)
    if (!token || role !== getCurrentRole()) return Promise.resolve()
    const key = role + ':' + token
    if (identity !== key) {
      generation++
      identity = key
      pending = null
      summary.value = null
    }
    if (pending) return pending
    const current = generation
    const revision = badgeRevision
    pending = getWorkbench(role).then(res => {
      if (current !== generation || role !== getCurrentRole() || token !== getTokenByRole(role)) return
      const previous = count('RELAY', 'pending')
      const next = res.data?.categories?.find(item => item.kind === 'RELAY')?.pending || 0
      if (role === 'cs' && summary.value && next > previous) {
        playMessageNotification()
        uni.showToast({ title: '收到新的接力申请', icon: 'none' })
      }
      if (revision === badgeRevision) summary.value = res.data
    }).catch(error => {
      if (current === generation && error?.code === 401) { summary.value = null; stopPolling() }
      // Preserve the last confirmed badges during a network interruption.
    }).finally(() => { if (current === generation) pending = null })
    return pending
  }

  // Ignore reads already in flight before a confirmed write, then reconcile from the server.
  function readConfirmed(kind, count) {
    if (identity !== getCurrentRole() + ':' + getTokenByRole(getCurrentRole())) {
      changed()
      return
    }
    const key = badgeCache[kind]
    if (key && summary.value && Number.isFinite(count)) {
      summary.value = { ...summary.value, [key]: Math.max(0, count) }
    }
    badgeRevision++
    changed()
  }

  async function refreshBadges() {
    const role = getCurrentRole()
    const token = getTokenByRole(role)
    if (!token) return
    // Multiple reads triggered by page show are still coalesced.
    await refresh(role)
  }

  function changed() {
    clearTimeout(refreshTimer)
    refreshTimer = setTimeout(async () => {
      if (pending) await pending
      refresh()
    }, 250)
  }

  function startPolling(role) {
    const token = getTokenByRole(role)
    if (!token) return clear()
    if (timer && identity === role + ':' + token) return
    stopPolling()
    refresh(role)
    timer = setInterval(() => refresh(role), 20000)
  }

  function stopPolling() {
    clearInterval(timer)
    clearTimeout(refreshTimer)
    timer = refreshTimer = null
  }

  function clear() {
    stopPolling()
    generation++
    identity = ''
    pending = null
    summary.value = null
  }

  return {
    summary, count, complaintUnread, relayUnread, replaceUnread, inviteCount, messageUnread, systemUnread, workbenchUnread,
    refresh, changed, readConfirmed, refreshBadges, fetchCsRemind: () => refresh('cs'), fetchPlayerRemind: () => refresh('player'),
    startPolling, stopPolling, clear
  }
})
