import request from '@/utils/request'

export function loadGovernance(kind, params) {
  return request({
    url: kind === 'privacy' ? '/admin/privacy/requests' : `/cs/governance/${kind}`,
    method: 'get',
    params
  })
}

export function createGovernanceInvitation(data) {
  return request({ url: '/cs/governance/invitations', method: 'post', data })
}

export function updateGovernanceInvitation(token, data) {
  return request({ url: `/cs/governance/invitations/${encodeURIComponent(token)}`, method: 'put', data })
}

export function reviewGovernanceRevision(id, data) {
  return request({ url: `/cs/governance/revisions/${id}`, method: 'put', data })
}

export function recheckGovernanceRevision(id) {
  return request({ url: `/cs/governance/revisions/${id}/recheck`, method: 'post' })
}

export function reviewPrivacyRequest(id, data) {
  return request({ url: `/admin/privacy/requests/${id}`, method: 'put', data })
}

export function updateGovernanceShift(id, data) {
  return request({ url: `/cs/governance/shifts/${id}`, method: 'put', data })
}

