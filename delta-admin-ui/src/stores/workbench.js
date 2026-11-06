import { defineStore } from 'pinia'
import { ref } from 'vue'
import request from '@/utils/request'
import { getToken } from '@/utils/auth'

export const useWorkbenchStore = defineStore('workbench', () => {
  const summary = ref(null)
  const error = ref('')
  const loading = ref(false)
  let timer
  let queued
  let pending
  let epoch = 0

  function refresh() {
    const token = getToken()
    if (!token || document.hidden) return Promise.resolve()
    if (pending) return pending
    const currentEpoch = epoch
    loading.value = true
    pending = request.get('/workbench/summary', { silent: true, loading: false })
      .then(res => {
        if (currentEpoch !== epoch || token !== getToken()) return
        summary.value = res.data
        error.value = ''
      })
      .catch(() => {
        if (currentEpoch === epoch) error.value = '待办刷新失败'
      })
      .finally(() => {
        if (currentEpoch !== epoch) return
        loading.value = false
        pending = null
      })
    return pending
  }

  function changed() {
    clearTimeout(queued)
    queued = setTimeout(async () => {
      if (pending) await pending
      refresh()
    }, 350)
  }

  function onVisibility() {
    if (!document.hidden) refresh()
  }

  function start() {
    if (timer) return
    refresh()
    timer = setInterval(refresh, 20000)
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('focus', onVisibility)
  }

  function stop() {
    epoch++
    clearInterval(timer)
    clearTimeout(queued)
    timer = queued = pending = null
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('focus', onVisibility)
    summary.value = null
    error.value = ''
    loading.value = false
  }

  return { summary, error, loading, refresh, changed, start, stop }
})
