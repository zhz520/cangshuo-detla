import request from '@/utils/request'

export function getWechatAccounts() {
  return request({ url: '/admin/wechat/accounts', method: 'get' })
}

export function saveWechatAccount(data) {
  return request({ url: '/admin/wechat/accounts', method: 'post', data })
}

export function deleteWechatAccount(id) {
  return request({ url: `/admin/wechat/accounts/${id}`, method: 'delete' })
}

export function getWechatTemplateCatalog(id) {
  return request({ url: `/admin/wechat/accounts/${id}/templates`, method: 'get' })
}

export function syncWechatTemplates(id) {
  return request({ url: `/admin/wechat/accounts/${id}/templates/sync`, method: 'post', data: {} })
}

export function saveWechatTemplate(id, data) {
  return request({ url: `/admin/wechat/accounts/${id}/templates`, method: 'put', data })
}

export function deleteWechatTemplate(id, role, scenario) {
  return request({ url: `/admin/wechat/accounts/${id}/templates/${role}/${scenario}`, method: 'delete', data: {} })
}

export function getWechatReadiness(id) {
  return request({ url: `/admin/wechat/accounts/${id}/readiness`, method: 'get' })
}

export function getWechatMenu(id) {
  return request({ url: `/admin/wechat/accounts/${id}/menu`, method: 'get' })
}

export function saveWechatMenu(id, data) {
  return request({ url: `/admin/wechat/accounts/${id}/menu`, method: 'post', data })
}

export function publishWechatMenu(id) {
  return request({ url: `/admin/wechat/accounts/${id}/menu/publish`, method: 'post', data: {} })
}

export function importWechatMenu(id) {
  return request({ url: `/admin/wechat/accounts/${id}/menu/import`, method: 'post', data: {} })
}

export function getWechatAutoReplies(id) {
  return request({ url: `/admin/wechat/accounts/${id}/autoreplies`, method: 'get' })
}

export function saveWechatAutoReply(id, ruleId, data) {
  return request({ url: ruleId ? `/admin/wechat/accounts/${id}/autoreplies/${ruleId}` : `/admin/wechat/accounts/${id}/autoreplies`, method: ruleId ? 'put' : 'post', data })
}

export function deleteWechatAutoReply(id, ruleId) {
  return request({ url: `/admin/wechat/accounts/${id}/autoreplies/${ruleId}`, method: 'delete', data: {} })
}

export function getWechatQrCodes(id) {
  return request({ url: `/admin/wechat/accounts/${id}/qrcodes`, method: 'get' })
}

export function createWechatQrCode(id, data) {
  return request({ url: `/admin/wechat/accounts/${id}/qrcodes`, method: 'post', data })
}

export function deleteWechatQrCode(id, qrId) {
  return request({ url: `/admin/wechat/accounts/${id}/qrcodes/${qrId}`, method: 'delete', data: {} })
}

export function getWechatFans(id) {
  return request({ url: `/admin/wechat/accounts/${id}/fans`, method: 'get' })
}

export function syncWechatFans(id) {
  return request({ url: `/admin/wechat/accounts/${id}/fans/sync`, method: 'post', data: {} })
}
