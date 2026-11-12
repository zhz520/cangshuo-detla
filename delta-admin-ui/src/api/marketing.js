import request from '@/utils/request'

export function getCouponTemplates() {
  return request({ url: '/admin/coupons', method: 'get', loading: false })
}

export function createCouponTemplate(data) {
  return request({ url: '/admin/coupons', method: 'post', data })
}

export function updateCouponTemplate(id, data) {
  return request({ url: `/admin/coupons/${id}`, method: 'put', data })
}

export function getInviteOperations() {
  return request({ url: '/admin/invites', method: 'get', loading: false })
}

export function markInviteDebtRecovered(id) {
  return request({ url: `/admin/invites/debts/${id}/recovered`, method: 'put' })
}

export function getCouponRecords() {
  return request({ url: '/admin/coupons/records', method: 'get', loading: false })
}

export function getMembershipConfig() {
  return request({ url: '/admin/membership', method: 'get', loading: false })
}

export function saveMembershipConfig(data) {
  return request({ url: '/admin/membership', method: 'put', data })
}
