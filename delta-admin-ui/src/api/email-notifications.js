import request from '@/utils/request'

export const getEmailNotificationSettings = (config = {}) => request.get('/admin/email-notifications/settings', config)
export const saveEmailNotificationSettings = data => request.put('/admin/email-notifications/settings', data)
export const sendEmailNotificationTest = data => request.post('/admin/email-notifications/test', data, { timeout: 30000 })
export const getEmailNotificationRecords = (config = {}) => request.get('/admin/email-notifications/records', { params: { limit: 200 }, ...config })
export const retryEmailNotification = id => request.post(`/admin/email-notifications/records/${encodeURIComponent(id)}/retry`)
