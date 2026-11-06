import { post } from './request'

export const createGuestOrder = (data) => post('/guest/orders', data, { auth: false })

export const lookupGuestOrder = (data) => post('/guest/lookup', data, { auth: false })

export const bindGuestOrder = (data) => post('/guest/bind', data)
