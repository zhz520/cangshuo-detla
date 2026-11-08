const WECHAT_ROLE_KEYS = Object.freeze({
  user: '客户', player: '打手', admin: '管理员', cs: '客服'
})
const SMS_ROLE_KEYS = WECHAT_ROLE_KEYS

const ORDER_FIELDS = {
  thing1: 'message',
  character_string2: 'orderNo',
  phrase3: 'status',
  amount4: 'amount',
  time5: 'time',
  thing6: 'customerName',
  thing7: 'playerName'
}
const REVIEW_FIELDS = {
  thing1: 'message',
  phrase2: 'status',
  time3: 'time'
}

function wechatExample(roleKey) {
  const role = WECHAT_ROLE_KEYS[roleKey]
  const template = (event, review = false) => ({
    templateId: `请替换为${role}-${event}-微信模板ID`,
    fields: review ? REVIEW_FIELDS : ORDER_FIELDS
  })
  return {
    new_order: template('新订单'),
    order_status: template('订单状态'),
    refund_status: template('退款状态'),
    complaint_status: template('投诉状态'),
    staff_review: template('人员审核', true)
  }
}

function smsExample(roleKey) {
  const role = SMS_ROLE_KEYS[roleKey]
  const template = (event, review = false) => ({
    templateCode: `请替换为${role}-${event}-阿里云短信模板Code`,
    fields: review
      ? { message: 'message', status: 'status', time: 'time' }
      : { orderNo: 'orderNo', status: 'status', amount: 'amount', customer: 'customerName', player: 'playerName', time: 'time' }
  })
  return {
    new_order: template('新订单'),
    order_status: template('订单状态'),
    refund_status: template('退款状态'),
    complaint_status: template('投诉状态'),
    staff_review: template('人员审核', true)
  }
}

export function notificationConfigExample(configKey) {
  const key = String(configKey || '')
  const wechat = key.match(/^wechat\.official\.templates\.(user|player|admin|cs)$/)
  if (wechat) return JSON.stringify(wechatExample(wechat[1]), null, 2)
  const sms = key.match(/^sms\.notification\.templates\.(user|player|admin|cs)$/)
  if (sms) return JSON.stringify(smsExample(sms[1]), null, 2)
  return ''
}
