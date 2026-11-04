import request from '@/utils/request'

export function login(data) {
  return request({ url: '/cs/auth/login', method: 'post', data })
}

export function getLoginCaptcha() {
  return request({ url: '/cs/auth/captcha', method: 'get', silent: true, loading: false })
}

export function logout() {
  return request({ url: '/auth/logout', method: 'post', silent: true, loading: false, timeout: 2000 })
}

/** 客服/管理员资料（改名字换头像） */
export function getCsProfile() {
  return request({ url: '/cs/profile', method: 'get' })
}

export function updateCsProfile(data) {
  return request({ url: '/cs/profile', method: 'put', data })
}

export function changeCsPassword(data) {
  return request({ url: '/cs/profile/password', method: 'put', data })
}
