import request from '@/utils/request'

// ==================== 管理员管理 ====================
export function getAdminList(params) {
  return request({ url: '/system/admin/list', method: 'get', params })
}
export function getAdminById(id) {
  return request({ url: `/system/admin/${id}`, method: 'get' })
}
export function addAdmin(data) {
  return request({ url: '/system/admin', method: 'post', data })
}
export function updateAdmin(data) {
  return request({ url: '/system/admin', method: 'put', data })
}
export function deleteAdmin(id) {
  return request({ url: `/system/admin/${id}`, method: 'delete' })
}

// ==================== 快捷回复管理 ====================
export function getQuickReplyList(params) {
  return request({ url: '/system/quick-reply/list', method: 'get', params })
}
export function addQuickReply(data) {
  return request({ url: '/system/quick-reply', method: 'post', data })
}
export function updateQuickReply(data) {
  return request({ url: '/system/quick-reply', method: 'put', data })
}
export function deleteQuickReply(id) {
  return request({ url: `/system/quick-reply/${id}`, method: 'delete' })
}

// ==================== 敏感词管理 ====================
export function getSensitiveWordList(params) {
  return request({ url: '/system/sensitive-word/list', method: 'get', params })
}
export function addSensitiveWord(data) {
  return request({ url: '/system/sensitive-word', method: 'post', data })
}
export function updateSensitiveWord(data) {
  return request({ url: '/system/sensitive-word', method: 'put', data })
}
export function deleteSensitiveWord(id) {
  return request({ url: `/system/sensitive-word/${id}`, method: 'delete' })
}

// ==================== 内容页面 ====================
export function getContentConfigList() {
  return request({ url: '/system/content/list', method: 'get' })
}
export function getContentConfigByKey(key) {
  return request({ url: `/system/content/key/${encodeURIComponent(key)}`, method: 'get', silent: true })
}
export function addContentConfig(data) {
  return request({ url: '/system/content', method: 'post', data })
}
export function updateContentConfig(data) {
  return request({ url: '/system/content', method: 'put', data })
}
export function deleteContentConfig(id) {
  return request({ url: `/system/content/${id}`, method: 'delete' })
}

// ==================== 公告管理 ====================
export function getNoticeList(params) {
  return request({ url: '/system/notice/list', method: 'get', params })
}
export function addNotice(data) {
  return request({ url: '/system/notice', method: 'post', data })
}
export function updateNotice(data) {
  return request({ url: '/system/notice', method: 'put', data })
}
export function deleteNotice(id) {
  return request({ url: `/system/notice/${id}`, method: 'delete' })
}

// ==================== 轮播图管理 ====================
export function getBannerList(params) {
  return request({ url: '/system/banner/list', method: 'get', params })
}
export function addBanner(data) {
  return request({ url: '/system/banner', method: 'post', data })
}
export function updateBanner(data) {
  return request({ url: '/system/banner', method: 'put', data })
}
export function deleteBanner(id) {
  return request({ url: `/system/banner/${id}`, method: 'delete' })
}

// ==================== 系统配置 ====================
export function getSysConfigList(options = {}) {
  return request({ url: '/system/config/list', method: 'get', ...options })
}
export function batchUpdateSysConfig(data) {
  return request({ url: '/system/config/batch', method: 'put', data })
}
export function getRechargePackages() {
  return request({ url: '/system/config/recharge-packages', method: 'get' })
}
export function updateRechargePackages(data) {
  return request({ url: '/system/config/recharge-packages', method: 'put', data })
}
export function uploadWxPayCertificate(configKey, file) {
  const formData = new FormData()
  formData.append('configKey', configKey)
  formData.append('file', file)
  return request({
    url: '/pay/config/upload',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function getSmsGatewayAccount() {
  return request({ url: '/admin/sms/account', method: 'get', silent: true })
}

export function checkoutSmsPackage(packageId) {
  return request({ url: `/admin/sms/packages/${encodeURIComponent(packageId)}/checkout`, method: 'post', silent: true })
}

const authorityServicePath = kind => kind === 'identity' ? '/admin/identity-service' : '/admin/sms'
export function getAuthoritySettings(kind) {
  return request({ url: authorityServicePath(kind) + '/settings', method: 'get', silent: true })
}
export function saveAuthoritySettings(kind, data) {
  return request({ url: authorityServicePath(kind) + '/settings', method: 'put', data, silent: true })
}
export function getIdentityLimits() {
  return request({ url: '/admin/identity-service/limits', method: 'get', silent: true })
}
export function saveIdentityLimits(data) {
  return request({ url: '/admin/identity-service/limits', method: 'put', data, silent: true })
}
export function getIdentityGatewayAccount() {
  return request({ url: authorityServicePath('identity') + '/account', method: 'get', silent: true })
}
export function checkoutIdentityPackage(packageId) {
  return request({ url: `/admin/identity-service/packages/${encodeURIComponent(packageId)}/checkout`, method: 'post', silent: true })
}

// ==================== 操作日志 ====================
export function getOperationLogList(params) {
  return request({ url: '/system/operation-log/list', method: 'get', params })
}

// ==================== 公告推送 ====================
export function pushNotice(id) {
  return request({ url: `/system/notice/${id}/push`, method: 'post' })
}
