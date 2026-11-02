import { reactive } from 'vue'

export const loadingFeedback = reactive({ requests: 0, destination: '', routeError: null, readError: null })

export function beginNavigation(to) {
  loadingFeedback.destination = to.fullPath
  loadingFeedback.routeError = null
  loadingFeedback.readError = null
}

export function finishNavigation(to) {
  // An older cancelled navigation must not finish a newer navigation's progress.
  if (loadingFeedback.destination === to.fullPath) loadingFeedback.destination = ''
}

export function failNavigation(error, to) {
  if (loadingFeedback.destination && loadingFeedback.destination !== to?.fullPath) return
  loadingFeedback.destination = ''
  loadingFeedback.routeError = { path: to?.fullPath || '/', message: error?.message || '页面加载失败' }
}
