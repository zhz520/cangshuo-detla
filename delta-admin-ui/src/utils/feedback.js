import { ElMessage, ElMessageBox } from 'element-plus'

const DEDUPE_WINDOW = 1500
const recent = new Map()

function durationFor(type, options = {}) {
  if (options.duration !== undefined) return options.duration
  if (type === 'error') return 5000
  if (type === 'warning') return 4000
  return 3000
}

function show(type, message, options = {}) {
  const text = String(message || '').trim()
  if (!text) return
  const now = Date.now()
  const key = type + ':' + text
  if ((recent.get(key) || 0) > now - DEDUPE_WINDOW) return
  recent.set(key, now)
  if (recent.size > 100) recent.clear()
  ElMessage({
    type,
    message: text,
    duration: durationFor(type, options),
    showClose: true,
    grouping: true,
    ...options
  })
}

export const toast = {
  success: (message, options) => show('success', message, options),
  info: (message, options) => show('info', message, options),
  warning: (message, options) => show('warning', message, options),
  error: (message, options) => show('error', message, options)
}

/**
 * Unified confirmation dialog. Returns true only when the user confirms.
 * Closing the dialog and cancelling both return false without throwing.
 */
export async function confirmAction(message, title = '请确认', options = {}) {
  try {
    await ElMessageBox.confirm(String(message || ''), String(title || '请确认'), {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      distinguishCancelAndClose: true,
      ...options
    })
    return true
  } catch (_) {
    return false
  }
}

