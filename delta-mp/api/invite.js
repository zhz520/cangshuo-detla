import { get, post } from './request'

export const getInviteInfo = () => get('/user/invites', {}, { role: 'user', loading: false })
export const bindInviteCode = code => post('/user/invites/bind', { code }, { role: 'user' })
