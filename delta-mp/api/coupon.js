import { get, post } from './request'

export const getAvailableCoupons = () => get('/user/coupons/available', {}, { role: 'user', loading: false, silent: true })
export const getMyCoupons = () => get('/user/coupons/mine', {}, { role: 'user', loading: false, silent: true })
export const claimCoupon = id => post('/user/coupons/' + encodeURIComponent(id) + '/claim', {}, { role: 'user', loading: false })
