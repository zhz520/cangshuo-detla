import request from '@/utils/request'

export const orderAssignmentCandidates = (admin, orderId, params) =>
  request({ url: `${admin ? '/admin' : '/cs'}/order/${orderId}/assign/candidates`, params, silent: true })
