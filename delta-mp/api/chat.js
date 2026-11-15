import { get, post } from './request'

/** 发送聊天消息 */
export const sendChatMessage = (data) => post('/common/chat/message/send', data)

/** 会话列表；接单员端传 opts.chatRole='PLAYER' 以便正确展示对方信息 */
export const getSessionList = (opts = {}) => {
  const query = opts.chatRole ? { chatRole: opts.chatRole } : {}
  return get('/common/chat/session/list', query, opts)
}

/** 消息列表（按 sessionId 分页）；接单员端传 chatRole: 'PLAYER'，用户端传 chatRole: 'USER' */
export const getChatMessageList = (params) => get('/common/chat/message/list', params)

/** 根据订单ID获取聊天会话（同一用户-接单员复用同一会话，带对方名称/头像）；接单员端传 opts.chatRole='PLAYER' */
export const getSessionByOrderId = (orderId, opts = {}) => {
  const params = opts.chatRole ? { chatRole: opts.chatRole } : {}
  return get(`/common/chat/session/by-order/${orderId}`, params)
}

/** 根据会话ID获取会话详情（含对方名称、头像）；接单员端传 opts.chatRole='PLAYER' */
export const getSessionDetail = (sessionId, opts = {}) => {
  const params = opts.chatRole ? { chatRole: opts.chatRole } : {}
  return get(`/common/chat/session/${sessionId}`, params)
}

/** 标记会话已读；接单员端传 opts.chatRole='PLAYER' */
export const markMessageRead = (sessionId, opts = {}) => {
  const q = opts.chatRole ? `&chatRole=${encodeURIComponent(opts.chatRole)}` : ''
  return post(`/common/chat/message/read?sessionId=${sessionId}${q}`, {})
}

/** 发起客服会话（自动分配客服） */
export const createCsSession = (params = {}) => {
  const query = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => encodeURIComponent(key) + '=' + encodeURIComponent(String(value)))
    .join('&')
  return post('/common/chat/session/cs' + (query ? '?' + query : ''))
}

/** 发送聊天短信提醒 */
export const sendChatSmsReminder = (data, opts = {}) => {
  const q = opts.chatRole ? `?chatRole=${encodeURIComponent(opts.chatRole)}` : ''
  return post(`/common/chat/sms-reminder${q}`, data)
}

/** 结束客服会话；关闭后消息只读 */
export const closeChatSession = (sessionId, opts = {}) => {
  const q = opts.chatRole ? `?chatRole=${encodeURIComponent(opts.chatRole)}` : ''
  return post(`/common/chat/session/${sessionId}/close${q}`, {})
}

/** 用户评价已结束的客服会话，每个会话仅可评价一次 */
export const rateChatSession = (sessionId, data, opts = {}) => {
  const q = opts.chatRole ? `?chatRole=${encodeURIComponent(opts.chatRole)}` : ''
  return post(`/common/chat/session/${sessionId}/rate${q}`, data)
}
