import { getToken } from '@/utils/auth'

const API_BASE = String(import.meta.env?.VITE_API_BASE_URL || '/api').replace(/\/+$/, '')

function attachmentError(message = '附件不可用', status) {
  const error = new Error(message)
  if (status) error.status = status
  return error
}

function normalizeToken(value) {
  const token = typeof value === 'string' ? value : value?.token
  return typeof token === 'string' ? token.trim() : ''
}

export function normalizeComplaintAttachmentList(value) {
  if (!Array.isArray(value)) return []
  const seen = new Set()
  return value.map(item => ({
    token: normalizeToken(item),
    kind: typeof item?.kind === 'string' ? item.kind.trim() : ''
  })).filter(item => {
    if (item.kind.toUpperCase() === 'UNAVAILABLE') return true
    if (!item.token || item.token === '.' || item.token === '..' || seen.has(item.token)) return false
    seen.add(item.token)
    return true
  })
}

export function legacyComplaintImageCount(detail) {
  const complaint = detail?.complaint || detail || {}
  const images = complaint.images
  if (Array.isArray(images)) return images.filter(Boolean).length
  if (typeof images !== 'string') return 0
  return images.split(',').filter(item => item.trim()).length
}

export function complaintAttachmentGroups(detail) {
  const payload = detail || {}
  const complaint = payload.complaint || payload
  const attachments = normalizeComplaintAttachmentList([
    ...(Array.isArray(complaint.attachments) ? complaint.attachments : []),
    ...(Array.isArray(payload.attachments) ? payload.attachments : [])
  ])
  const appealAttachments = normalizeComplaintAttachmentList([
    ...(Array.isArray(complaint.appealAttachments) ? complaint.appealAttachments : []),
    ...(Array.isArray(payload.appealAttachments) ? payload.appealAttachments : [])
  ])
  return {
    attachments,
    appealAttachments,
    legacyImageCount: attachments.length ? 0 : legacyComplaintImageCount(payload)
  }
}

function attachmentUrl(complaintId, token) {
  const id = String(complaintId || '')
  if (!/^\d+$/.test(id) || id === '0') throw attachmentError('投诉参数无效', 400)
  const value = normalizeToken(token)
  if (!value || value === '.' || value === '..') throw attachmentError('附件不可用', 400)
  return `${API_BASE}/cs/complaint/${encodeURIComponent(id)}/attachment/${encodeURIComponent(value)}`
}

/** 当前客服 token 只放 Authorization；图片标签只使用调用方生成的 blob URL。 */
export async function fetchCsComplaintAttachment(complaintId, token, options = {}) {
  const authToken = getToken()
  if (!authToken) throw attachmentError('附件不可用', 401)
  const url = attachmentUrl(complaintId, token)
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (options.signal?.aborted) controller.abort()
  else options.signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(abort, options.timeout || 30000)
  try {
    const response = await fetch(url, {
      method: 'GET',
      headers: { Authorization: `Bearer ${authToken}` },
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal
    })
    if (!response.ok) throw attachmentError('附件不可用', response.status)
    const blob = await response.blob()
    if (!blob || !blob.size) throw attachmentError('附件不可用', 404)
    return blob
  } finally {
    clearTimeout(timer)
    options.signal?.removeEventListener('abort', abort)
  }
}
