import { get, put } from './request'

export const getSpendRanking = () => get('/app/display/spend-ranking', {}, { auth: false, loading: false })
export const getPurchaseFeed = () => get('/app/display/purchase-feed', {}, { auth: false, loading: false })
export const getDisplayPrivacy = () => get('/user/display/privacy', {}, { loading: false })
export const updateDisplayPrivacy = data => put('/user/display/privacy', data, { loading: false })
