import request from '@/utils/request'

export function getOfficialWechatBinding() {
  return request({ url: '/common/wechat/binding', method: 'get', silent: true })
}

export function startOfficialWechatBinding() {
  return request({ url: '/common/wechat/binding/start', method: 'post', data: {} })
}

export function completeOfficialWechatBinding(data) {
  return request({ url: '/common/wechat/binding/complete', method: 'post', data })
}

export function removeOfficialWechatBinding() {
  return request({ url: '/common/wechat/binding', method: 'delete', data: {} })
}
