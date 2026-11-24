import request from '@/utils/request'

// ==================== 用户管理 ====================
export function adminUserList(params) {
  return request({ url: '/admin/user/list', method: 'get', params })
}
export function adminUserDetail(id) {
  return request({ url: `/admin/user/${id}`, method: 'get' })
}
export const adminUserCreate = data => request.post('/admin/user',data)
export const adminUserUpdate = (id,data) => request.put(`/admin/user/${id}`,data)
export const adminUserDelete = id => request.delete(`/admin/user/${id}`)
export function adminUserUpdateStatus(id, status) {
  return request({ url: `/admin/user/${id}/status`, method: 'put', params: { status } })
}
export const adminUserBatchStatus = (ids, status) => request.post('/admin/user/batch/status', { ids, status })
export const adminUserBatchDelete = ids => request.post('/admin/user/batch/delete', { ids })
export function adminUserAdjustBalance(id, data) {
  return request({ url: `/admin/user/${id}/balance`, method: 'put', data })
}
export function csUserList(params) {
  return request({ url: '/cs/user/list', method: 'get', params })
}
export function csUserDetail(id) {
  return request({ url: `/cs/user/${id}`, method: 'get' })
}
export function csUserUpdateStatus(id, status) {
  return request({ url: `/cs/user/${id}/status`, method: 'put', data: { status } })
}

// ==================== 打手管理 ====================
export function adminPlayerList(params, config = {}) {
  return request({ ...config, url: '/admin/player/list', method: 'get', params })
}
export function adminPlayerDetail(id) {
  return request({ url: `/admin/player/${id}`, method: 'get' })
}
export function adminPlayerTransactions(id, params) {
  return request({ url: `/admin/player/${id}/transactions`, method: 'get', params })
}
export function adminPlayerDeposits(id) {
  return request({ url: `/admin/player/${id}/deposits`, method: 'get' })
}
export function adminSetPlayerDepositAmount(id, amount) {
  return request({ url: `/admin/player/${id}/deposit-amount`, method: 'put', data: { amount } })
}
export function adminPlayerDepositList(params) {
  return request({ url: '/admin/player/deposits', method: 'get', params })
}
export function adminPlayerApprove(id, levelId) {
  return request({ url: `/admin/player/${id}/approve`, method: 'put', params: levelId == null ? undefined : { levelId } })
}
export function adminPlayerReject(id, reason) {
  return request({ url: `/admin/player/${id}/reject`, method: 'put', params: { reason } })
}
export function adminPlayerUpdateStatus(id, status) {
  return request({ url: `/admin/player/${id}/status`, method: 'put', params: { status } })
}
export function adminPlayerFreeze(id, data) {
  return request({ url: `/admin/player/${id}/freeze`, method: 'put', data })
}
export function adminPlayerUnfreeze(id) {
  return request({ url: `/admin/player/${id}/unfreeze`, method: 'put' })
}
export function adminPlayerDelete(id) {
  return request({ url: `/admin/player/${id}`, method: 'delete' })
}
export const adminPlayerBatchApprove = ids => request.post('/admin/player/batch/approve', { ids })
export const adminPlayerBatchReject = (ids, reason) => request.post('/admin/player/batch/reject', { ids, reason })
export const adminPlayerBatchLevel = (ids, levelId) => request.post('/admin/player/batch/level', { ids, levelId })
export const adminPlayerBatchUnfreeze = ids => request.post('/admin/player/batch/unfreeze', { ids })
export function adminPlayerUpdate(id, data) {
  return request({ url: `/admin/player/${id}`, method: 'put', data })
}
export function adminReviewList(params) {
  return request({ url: '/admin/review/list', method: 'get', params })
}
export function adminReviewDelete(id) {
  return request({ url: `/admin/review/${id}`, method: 'delete' })
}
export function csPlayerList(params, config = {}) {
  return request({ ...config, url: '/cs/player/list', method: 'get', params })
}
export function csPlayerDetail(id) {
  return request({ url: `/cs/player/${id}`, method: 'get' })
}
export function csPlayerTransactions(id, params) {
  return request({ url: `/cs/player/${id}/transactions`, method: 'get', params })
}
export function csPlayerAudit(id, status, rejectReason) {
  return request({ url: `/cs/player/${id}/audit`, method: 'put', data: { status, rejectReason } })
}
export function csPlayerFreeze(id, data) {
  return request({ url: `/cs/player/${id}/freeze`, method: 'put', data })
}
export function csPlayerUnfreeze(id) {
  return request({ url: `/cs/player/${id}/unfreeze`, method: 'put' })
}
export function csPlayerUpdateNickname(id, nickname) {
  return request({ url: `/cs/player/${id}/nickname`, method: 'put', data: { nickname } })
}

// ==================== 订单管理 ====================
export function adminOrderList(params, config = {}) {
  return request({ ...config, url: '/admin/order/list', method: 'get', params })
}
export function adminOrderDetail(id) {
  return request({ url: `/admin/order/${id}`, method: 'get' })
}
export function adminOrderAssign(orderId, playerId) {
  return request({ url: `/admin/order/${orderId}/assign/${playerId}`, method: 'post' })
}
export function adminOrderReassignCs(orderId, csId) {
  return request({ url: '/admin/order/' + orderId + '/cs', method: 'put', data: { csId } })
}
export function csOrderAssign(orderId, playerId) {
  return request({ url: `/cs/order/${orderId}/assign/${playerId}`, method: 'post' })
}
export function adminOrderRefund(orderId) {
  return request({ url: `/admin/order/${orderId}/refund`, method: 'post' })
}
export function adminOrderConfirm(orderId) {
  return request({ url: `/admin/order/${orderId}/confirm`, method: 'post' })
}
export function csOrderRefund(orderId) {
  return request({ url: `/cs/order/${orderId}/refund`, method: 'post' })
}

// ==================== 两阶段退款 ====================
export function customerRefundHistory(orderId) {
  return request({ url: `/order/${orderId}/refunds`, method: 'get' })
}
export function customerRefundCreate(orderId, data) {
  return request({ url: `/order/${orderId}/refunds`, method: 'post', data })
}
export function csRefundCreate(orderId, data) {
  return request({ url: `/cs/refunds/${orderId}`, method: 'post', data })
}
export function csRefundList(params) {
  return request({ url: '/cs/refunds', method: 'get', params })
}
export function csRefundDetail(id) {
  return request({ url: `/cs/refunds/${id}`, method: 'get' })
}
export function csRefundAudit(id) {
  return request({ url: `/cs/refunds/${id}/audit`, method: 'get' })
}
export function csRefundStartReview(id) {
  return request({ url: `/cs/refunds/${id}/review/start`, method: 'post' })
}
export function csRefundSubmitReview(id, data) {
  return request({ url: `/cs/refunds/${id}/review`, method: 'post', data })
}
export function adminRefundList(params) {
  return request({ url: '/admin/refunds', method: 'get', params })
}
export function adminRefundDetail(id) {
  return request({ url: `/admin/refunds/${id}`, method: 'get' })
}
export function adminRefundApprove(id, data = {}) {
  return request({ url: `/admin/refunds/${id}/approve`, method: 'post', data })
}
export function adminRefundReject(id, data) {
  return request({ url: `/admin/refunds/${id}/reject`, method: 'post', data })
}
export function adminRefundRetry(id) {
  return request({ url: `/admin/refunds/${id}/retry`, method: 'post' })
}
export function csOrderConfirm(orderId) {
  return request({ url: `/cs/order/${orderId}/confirm`, method: 'post' })
}
export function csOrderList(params, config = {}) {
  return request({ ...config, url: '/cs/order/list', method: 'get', params })
}

// ==================== 投诉管理 ====================
export function csComplaintList(params) {
  return request({ url: '/cs/complaint/list', method: 'get', params })
}
export function csComplaintProcess(id, remark) {
  return request({ url: `/cs/complaint/${id}/process`, method: 'put', data: { remark } })
}
export function csComplaintResolve(id, data) {
  return request({ url: `/cs/complaint/${id}/resolve`, method: 'put', data })
}
export function csComplaintCreate(data) {
  return request({ url: '/cs/complaint/create', method: 'post', data })
}

// 客服端投诉详情
export function csComplaintDetail(id) {
  return request({ url: `/cs/complaint/${id}`, method: 'get' })
}

// ==================== 提现管理 ====================
export function adminWithdrawList(params) {
  return request({ url: '/admin/withdraw/list', method: 'get', params })
}
export function adminWithdrawProcess(id, data) {
  return request({ url: `/admin/withdraw/${id}/process`, method: 'put', data })
}
export function adminWithdrawReject(id, reason) {
  return request({ url: `/admin/withdraw/${id}/reject`, method: 'put', params: { reason } })
}
export function depositRefundList(params) {
  return request({ url: '/cs/deposit-refunds/list', method: 'get', params })
}
export function depositRefundReview(id, data) {
  return request({ url: `/cs/deposit-refunds/${id}/review`, method: 'put', data })
}
export function depositRefundRetry(id) {
  return request({ url: `/cs/deposit-refunds/${id}/retry`, method: 'post' })
}
export function csWithdrawList(params) {
  return request({ url: '/cs/withdraw/list', method: 'get', params })
}
export function csWithdrawApprove(id, data) {
  return request({ url: `/cs/withdraw/${id}/approve`, method: 'put', data })
}
export function csWithdrawReject(id, reason) {
  return request({ url: `/cs/withdraw/${id}/reject`, method: 'put', data: { reason } })
}

// ==================== 聊天记录管理 ====================
export function adminChatSessionList(params) {
  return request({ url: '/admin/chat/session/list', method: 'get', params })
}
export function adminChatMessageList(params) {
  return request({ url: '/admin/chat/message/list', method: 'get', params })
}

// ==================== 订单详情 / 进度 ====================
export function csOrderDetail(id) {
  return request({ url: `/cs/order/${id}`, method: 'get' })
}
export function csOrderProgress(id) {
  return request({ url: `/cs/order/${id}/progress`, method: 'get' })
}
export function adminOrderProgress(id) {
  return request({ url: `/admin/order/${id}/progress`, method: 'get' })
}
export function csChatSessionAllList(params) {
  return request({ url: '/cs/chat/session/list', method: 'get', params })
}
export function csChatMessageList(params) {
  return request({ url: '/cs/chat/message/list', method: 'get', params })
}

// ==================== 快捷回复 ====================
export function getActiveQuickReplies(category) {
  return request({ url: '/system/quick-reply/active', method: 'get', params: { category } })
}

// ==================== 客服实时聊天 ====================
export function csChatSessionList(params, config = {}) {
  return request({ ...config, url: '/cs/chat/sessions', method: 'get', params })
}
export function chatMessageList(params) {
  return request({ url: '/common/chat/message/list', method: 'get', params })
}
export function chatSendMessage(data) {
  return request({ url: '/common/chat/message/send', method: 'post', data })
}
export function chatMarkRead(sessionId) {
  return request({ url: '/common/chat/message/read', method: 'post', params: { sessionId } })
}

// ==================== 换人申请 ====================
export function csReplaceList(params) {
  return request({ url: '/cs/replace/list', method: 'get', params })
}
export function csReplaceApprove(id, remark) {
  return request({ url: `/cs/replace/${id}/approve`, method: 'put', data: { remark } })
}
export function csReplaceReject(id, remark) {
  return request({ url: `/cs/replace/${id}/reject`, method: 'put', data: { remark } })
}

// ==================== 接力申请 ====================
export function csRelayList(params) {
  return request({ url: '/cs/relay/list', method: 'get', params })
}
export function csRelayApprove(id, newPlayerId) {
  return request({ url: `/cs/relay/${id}/approve`, method: 'post', data: { newPlayerId } })
}
export function csRelayReject(id, reason) {
  return request({ url: `/cs/relay/${id}/reject`, method: 'post', data: reason ? { reason } : {} })
}

// ==================== 打手等级 ====================
export const playerLevelList = () => request({url:'/admin/player-level/list',method:'get'})
export const playerLevelCreate = data => request({url:'/admin/player-level',method:'post',data})
export const playerLevelUpdate = (id,data) => request({url:`/admin/player-level/${id}`,method:'put',data})
export const playerLevelDelete = id => request({url:`/admin/player-level/${id}`,method:'delete'})

// ==================== 打手展示布局 ====================
export const getPlayerLayoutSettings = () => request({url:'/admin/player-layout',method:'get'})
export const savePlayerLayoutSettings = layout => request({url:'/admin/player-layout',method:'put',data:{layout}})

// 兼容现有 business.js 调用方；上传接口实现见 file-upload.js。
export { uploadFile, uploadProfileAudio } from './file-upload.js'

// ==================== 结算台账 ====================
export function getSettlementLedger(params) {
  return request({ url: '/admin/settlements', method: 'get', params })
}

