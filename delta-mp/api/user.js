import { get, post, put, del, uploadFile } from './request'

/** 获取用户资料（始终使用 user_token） */
export const getUserProfile = (opts = {}) => get('/user/profile', {}, { role: 'user', ...opts })

/** 修改用户资料（始终使用 user_token） */
export const updateUserProfile = (data) => put('/user/profile', data, { role: 'user' })

/** 修改手机号（始终使用 user_token） */
export const updatePhone = (data) => put('/user/phone', data, { role: 'user' })
export const sendPhoneBindCode = data => post('/user/phone/code', data, { role: 'user' })
export const linkWechatAccount = data => post('/user/auth/wechat/link-account', data, { role: 'user' })

/** 获取钱包信息（始终使用 user_token） */
export const getWallet = () => get('/user/wallet', {}, { role: 'user' })

/** 已保存的游戏信息列表（下单页可选择复用） */
export const getGameInfoList = () => get('/user/game-info/list', {}, { role: 'user' })

/** 保存当前填写的游戏信息，下次下单可选择 */
export const saveGameInfo = (data) => post('/user/game-info', data, { role: 'user' })

/** 按分类获取已保存的动态字段信息 */
export const getSavedInfoByCategory = (categoryId) => get(`/user/game-info/list/${categoryId}`, {}, { role: 'user' })

/** 保存动态字段信息 */
export const saveDynamicInfo = (data) => post('/user/game-info/dynamic', data, { role: 'user' })

/** 删除已保存信息 */
export const deleteSavedInfo = (id) => del(`/user/game-info/${id}`, {}, { role: 'user' })

/** 实名认证 */
export const getIdentityStatus = (opts = {}) => get('/user/identity/status', {}, { role: 'user', ...opts })
export const uploadIdentityImage = (filePath, side) => uploadFile(filePath, `/user/identity/image?side=${encodeURIComponent(side)}`, { role: 'user' })
export const submitIdentityVerification = data => post('/user/identity/submit', data, { role: 'user' })
export const autoVerifyIdentity = data => post('/user/identity/auto-verify', data, { role: 'user' })

/** 商品收藏 */
export const getFavoriteStatus = productId => get(`/user/favorites/${productId}/status`, {}, { role: 'user', loading: false })
export const addFavorite = productId => post(`/user/favorites/${productId}`, {}, { role: 'user' })
export const removeFavorite = productId => del(`/user/favorites/${productId}`, {}, { role: 'user' })
export const getFavorites = params => get('/user/favorites', params, { role: 'user' })

/** 打手关注 */
export const getFollowStatus = playerId => get(`/user/follows/${playerId}/status`, {}, { role: 'user', loading: false })
export const followPlayer = playerId => post(`/user/follows/${playerId}`, {}, { role: 'user' })
export const unfollowPlayer = playerId => del(`/user/follows/${playerId}`, {}, { role: 'user' })
export const getFollows = params => get('/user/follows', params, { role: 'user' })
