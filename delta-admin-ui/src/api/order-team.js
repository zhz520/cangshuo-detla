import request from '@/utils/request'

function root(admin, id) {
  return (admin ? '/admin' : '/cs') + '/order/' + id + '/teammates'
}
export const orderTeamRoster = (admin, id) => request({ url: root(admin, id), silent: true })
export const orderTeamCandidates = (admin, id, params) => request({ url: root(admin, id) + '/candidates', params, silent: true })
export const orderTeamInvite = (admin, id, data) => request({ url: root(admin, id) + '/invite', method: 'post', data, silent: true })
export const orderTeamRevoke = (admin, id, memberId) => request({ url: root(admin, id) + '/' + memberId + '/revoke', method: 'post', silent: true })
export const orderTeamRemove = (admin, id, memberId, reason) => request({ url: root(admin, id) + '/' + memberId + '/remove', method: 'post', data: { reason }, silent: true })
