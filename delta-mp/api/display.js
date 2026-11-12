import { get } from './request'

export const getSpendRanking = () => get('/app/display/spend-ranking', {}, { auth: false, loading: false })
export const getPurchaseFeed = () => get('/app/display/purchase-feed', {}, { auth: false, loading: false })
