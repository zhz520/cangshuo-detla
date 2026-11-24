import { downloadPrivateFile, get, post, releasePrivateFileUrl, uploadFile } from './request'
import { getUserToken } from '@/utils/auth'

function customerAttachmentRole() {
  return getUserToken() ? 'user' : 'guest'
}

export function normalizeAttachmentToken(value) {
  const token = typeof value === 'string' ? value : value?.token
  return typeof token === 'string' ? token.trim() : ''
}

export function normalizeAttachmentList(value) {
  if (!Array.isArray(value)) return []
  const seen = new Set()
  return value.map(item => ({
    token: normalizeAttachmentToken(item),
    kind: typeof item?.kind === 'string' ? item.kind.trim() : ''
  })).filter(item => {
    if (item.kind.toUpperCase() === 'UNAVAILABLE') return true
    if (!item.token || item.token === '.' || item.token === '..' || seen.has(item.token)) return false
    seen.add(item.token)
    return true
  })
}

export function legacyComplaintImageCount(complaint) {
  const images = complaint?.images
  if (Array.isArray(images)) return images.filter(Boolean).length
  if (typeof images !== 'string') return 0
  return images.split(',').filter(item => item.trim()).length
}

function invalidToken() {
  const error = new Error('附件标识无效')
  error.code = 'INVALID_ATTACHMENT_TOKEN'
  return error
}

/** 上传投诉私有附件，后端只返回访问 token。 */
export function uploadComplaintAttachment(orderId, filePath) {
  const id = String(orderId || '')
  if (!/^\d+$/.test(id) || id === '0') return Promise.reject(new Error('订单参数异常'))
  return uploadFile(filePath, `/order/complaint/attachment?orderId=${encodeURIComponent(id)}`, { role: customerAttachmentRole() })
    .then(value => {
      const token = normalizeAttachmentToken(value)
      if (!token || token === '.' || token === '..') throw new Error('附件上传响应异常，请重试')
      return token
    })
}

/** 当前客户用 Authorization 读取私有附件，返回本地 blob URL 或临时文件路径。 */
export function getComplaintAttachment(token) {
  const value = normalizeAttachmentToken(token)
  if (!value || value === '.' || value === '..') return Promise.reject(invalidToken())
  return downloadPrivateFile(`/order/complaint/attachment/${encodeURIComponent(value)}`, { role: customerAttachmentRole() })
}

export function releaseComplaintAttachmentUrl(source) {
  releasePrivateFileUrl(source)
}

/** 提交投诉 */
export const createComplaint = (data) => post('/order/complaint', data)

/** 投诉详情 */
export const getComplaintDetail = (id) => get(`/order/complaint/${id}`)

/** 申诉 */
export const appealComplaint = (id, data) => post(`/order/complaint/${id}/appeal`, data)

/** 我的投诉列表 */
export const getMyComplaints = (params) => get('/order/complaint/my', params, { loading: false })
