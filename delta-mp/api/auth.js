import { get, post } from './request'
export const getLoginCaptcha = () => get('/user/auth/captcha', {}, { auth: false, loading: false })
export const getCsLoginCaptcha = () => get('/cs/auth/captcha', {}, { auth: false, loading: false })
export const csLogin = data => post('/cs/auth/login', data, { auth: false, loading: false })
export const sendResetCode=data=>post('/user/auth/reset-code',data,{auth:false})
export const resetPassword=data=>post('/user/auth/reset-password',data,{auth:false})
export const sendLoginCode = (data) => post('/user/auth/sms-code', data, { auth: false })

/** 用户微信登录 */
export const userLogin = (code, phoneCode) => post('/user/auth/login', { code, phoneCode, privacyAccepted:true }, { auth: false })

/** H5 手机号验证码登录 */
export const h5Login = (data) => post('/user/auth/h5-login', data, { auth: false })
export const setupUserPassword = data => post('/user/auth/set-password',data,{auth:false})
export const userPasswordLogin = data => post('/user/auth/password-login',data,{auth:false})
export const getLoginPolicy = () => get('/user/auth/login-policy', {}, { auth: false, loading: false })
export const getWechatLoginUrl = returnTo => get('/user/auth/wechat/start', { returnTo }, { auth: false, loading: false })
export const exchangeWechatTicket = ticket => post('/user/auth/wechat/exchange', { ticket }, { auth: false })

/** Revoke a token explicitly before removing it from local storage. */
export const revokeSession = token => post('/auth/logout', {}, {
  auth: false, loading: false, silent: true, timeout: 1500,
  header: token ? { Authorization: `Bearer ${token}` } : {}
})

/** 用户切换到打手端：携带 user_token，返回 player_token（每次切换都需重新获取） */
export const switchToPlayerToken = () => post('/user/auth/switch-to-player', {}, { role: 'user', loading: true })
