import request from '@/utils/request'

export function getShiftStatus() {
  return request({ url: '/cs/shift/status', method: 'get', silent: true, loading: false })
}

export function toggleShift(online) {
  return request({ url: '/cs/shift/toggle', method: 'post', data: { online } })
}
