import request from '@/utils/request'

export const getFeatureMarket = () => request.get('/admin/platform/market', { silent: true })
export const syncFeatureMarket = () => request.post('/admin/platform/market/sync', {}, { timeout: 55000, silent: true })
export const getPlatformOverview = () => request.get('/admin/platform/overview')
export const getPlatformFeatures = (config = {}) => request.get('/admin/platform/features', config)
export const getPlatformUpdates = () => request.get('/admin/platform/updates')
export const setPlatformUpdateSelection = components => request.put('/admin/platform/updates/selection', { components })
export const importPlatformLicense = envelope => request.post('/admin/platform/license/import', envelope)
export const setPlatformFeatureEnabled = (id, enabled) => request.put(`/admin/platform/features/${encodeURIComponent(id)}/enabled`, { enabled })
export const createPlatformCheckout = moduleId => request.post('/admin/platform/checkout', { moduleId }, { timeout: 55000 })
export const getIncomeComparison = params => request.get('/admin/platform/reports/income', { params })

export const getRemoteUpdates = (config = {}) => request.get('/admin/platform/updates/remote', config)
export const checkRemoteUpdates = () => request.post('/admin/platform/updates/check', {}, { timeout: 55000 })
export const installRemoteUpdates = selection => request.post('/admin/platform/updates/install', { selection }, { timeout: 55000 })
