import request from '@/utils/request'

export const getIdentityApplications = params => request.get('/admin/identity/applications', { params })
export const getIdentityApplication = userId => request.get(`/admin/identity/applications/${encodeURIComponent(userId)}`)
export const reviewIdentityApplication = (userId, data) => request.put(`/admin/identity/applications/${encodeURIComponent(userId)}`, data)
