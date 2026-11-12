import { get } from './request'

export const getMembership = () => get('/user/membership', {}, { role: 'user', loading: false })
