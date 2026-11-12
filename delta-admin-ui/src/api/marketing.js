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
