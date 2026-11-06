import { clientStorage } from '@/utils/client-storage'
/**
 * 统一请求封装
 * 自动注入token、统一错误处理、loading管理
 */
import { getCurrentRole, getTokenByRole, removeGuestSession } from '@/utils/auth'
import { createLoadingController } from '@/utils/loading'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

let _handlingUnauthorized = false  // 防止多个401同时触发重复弹出跳转
const pendingReads = new Map()
let readGeneration = 0
const loaders = createLoadingController({ show: options => uni.showLoading(options), hide: () => { try { uni.hideLoading() } catch (_) {} } })

/**
 * 核心请求函数
 * @param {Object} options
 * @param {string} options.url - 接口路径（不含baseURL）
 * @param {string} options.method - GET/POST/PUT/DELETE
 * @param {Object} options.data - 请求参数
 * @param {boolean} options.loading - 是否显示loading，默认true
 * @param {boolean} options.auth - 是否需要鉴权，默认true
 * @param {string} options.role - 指定角色获取token，默认当前角色
 */
export function request(options) {
  const role = options.role || getCurrentRole()
  // Only coalesce simultaneous reads, never cache responses or combine writes.
  if ((options.method || 'GET') !== 'GET') { readGeneration++; return performRequest(options) }
  const key = JSON.stringify([readGeneration, options.url, options.data || {}, role, options.auth === false ? '' : getTokenByRole(role), options.header || {}, options.loading !== false, options.redirectOnUnauthorized !== false])
  if (pendingReads.has(key)) return pendingReads.get(key)
  const task = performRequest(options).finally(() => { if (pendingReads.get(key) === task) pendingReads.delete(key) })
  pendingReads.set(key, task)
  return task
}

function performRequest(options) {
  const {
    url,
    method = 'GET',
    data = {},
    loading = true,
    auth = true,
    role,
    header = {},
    timeout = 20000,
    silent = false,
    redirectOnUnauthorized = true
  } = options

  return new Promise((resolve, reject) => {
    const finishLoading = loading ? loaders.begin(method !== 'GET') : () => {}

    // 注入token
    const currentRole = role || getCurrentRole()
    const requestToken = auth ? getTokenByRole(currentRole) : ''
    const requestHeader = { ...header }
    if (auth) {
      if (requestToken) {
        requestHeader['Authorization'] = `Bearer ${requestToken}`
      }
    }

    const _startTime = Date.now()
    let finalUrl = `${BASE_URL}${url}`
    let bodyData = data
    if (method === 'GET' && data && Object.keys(data).length > 0) {
      const qs = Object.entries(data)
        .filter(([, v]) => v !== undefined && v !== null && v !== '')
        .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
        .join('&')
      if (qs) finalUrl += (url.includes('?') ? '&' : '?') + qs
      bodyData = {}
    }
    if (import.meta.env.DEV) console.log(`[REQ] >>> ${method} ${url}`)
    uni.request({
      url: finalUrl,
      method,
      timeout,
      data: bodyData,
      header: {
        'Content-Type': 'application/json',
        ...requestHeader
      },
      success(res) {
        if (import.meta.env.DEV) console.log(`[REQ] <<< ${method} ${url} ${Date.now() - _startTime}ms`)
        const { statusCode, data: resData } = res

        if (statusCode === 503 && resData?.maintenance) {
          finishLoading()
          const pages = getCurrentPages()
          if (pages[pages.length-1]?.route !== 'pages/maintenance/index') uni.reLaunch({ url: '/pages/maintenance/index' })
          reject(resData)
          return
        }
        if (statusCode === 200) {
          // 业务层code判断
          if (resData?.code === 0 || resData?.code === 200) {
            resolve(resData)
          } else if (resData?.code === 401) {
            // token失效
            finishLoading()
            if (redirectOnUnauthorized) handleUnauthorized(currentRole, requestToken)
            reject(resData)
          } else {
            // 业务错误
            finishLoading()
            if (!silent) uni.showToast({ title: resData?.msg || '请求失败', icon: 'none' })
            reject(resData)
          }
        } else if (statusCode === 401) {
          finishLoading()
          if (redirectOnUnauthorized) handleUnauthorized(currentRole, requestToken)
          reject(resData)
        } else {
          finishLoading()
          if (!silent) uni.showToast({ title: statusCode >= 500 ? '服务暂时不可用，请稍后重试' : `网络错误(${statusCode})`, icon: 'none' })
          reject(resData)
        }
      },
      fail(err) {
        finishLoading()
        if (!silent) uni.showToast({ title: err?.errMsg?.includes('timeout') ? '请求超时，请重试' : '网络连接失败', icon: 'none' })
        reject(err)
      },
      complete() {
        finishLoading()
      }
    })
  })
}

/**
 * 处理未授权
 */
function handleUnauthorized(role, expectedToken) {
  if (expectedToken !== undefined && expectedToken !== getTokenByRole(role)) return
  // A late response from another role must not interrupt the active session.
  if (role !== getCurrentRole()) return
  // 防止多个并发401请求重复触发弹出和跳转
  if (_handlingUnauthorized) return
  _handlingUnauthorized = true

  if (role === 'guest') {
    removeGuestSession()
    uni.showToast({ title: '游客会话已过期，请重新查单', icon: 'none' })
    uni.reLaunch({ url: '/pages/order/lookup' })
    setTimeout(() => { _handlingUnauthorized = false }, 1500)
    return
  }
  if (role === 'cs') {
    clientStorage.remove('cs_token')
  } else if (role === 'player') {
    clientStorage.remove('player_token')
  } else {
    clientStorage.remove('user_token')
  }

  uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
  const redirect = currentRedirectPath()
  if (role === 'cs') {
    uni.reLaunch({ url: '/pages/login/index?expired=1'+(redirect?'&redirect='+encodeURIComponent(redirect):'') })
  } else if (role === 'player') {
    clientStorage.set('app_role', 'user')
    uni.reLaunch({ url: '/pages/mine/index' })
  } else {
    uni.reLaunch({ url: '/pages/login/index?expired=1'+(redirect?'&redirect='+encodeURIComponent(redirect):'') })
  }
  setTimeout(() => { _handlingUnauthorized = false }, 1500)
}

function currentRedirectPath() {
  const page = getCurrentPages().slice(-1)[0]
  if (!page) return ''
  let fullPath = page.$page?.fullPath || page.fullPath || ''
  if (!fullPath && page.route) {
    const query = Object.entries(page.options || {})
      .filter(([, value]) => value !== undefined && value !== null && value !== '')
      .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
      .join('&')
    fullPath = '/' + page.route + (query ? '?' + query : '')
  }
  if (!fullPath.startsWith('/')) fullPath = '/' + fullPath
  if (!fullPath.startsWith('/pages/') || fullPath.startsWith('/pages/login/') || fullPath.includes('://')) return ''
  return fullPath
}

/**
 * 文件上传
 */
export function uploadFile(filePath, endpoint = '/common/file/upload', options = {}) {
  return new Promise((resolve, reject) => {
    const role = options.role || getCurrentRole()
    const token = getTokenByRole(role)
    const header = {}
    if (token) {
      header['Authorization'] = `Bearer ${token}`
    }

    uni.uploadFile({
      url: `${BASE_URL}${endpoint}`,
      filePath,
      name: 'file',
      header,
      timeout: 60000,
      success(res) {
        if (res.statusCode === 200) {
          let data
          try { data = JSON.parse(res.data) } catch (_) { reject(new Error('上传响应异常，请重试')); return }
          if (data.code === 0 || data.code === 200) {
            resolve(data.data) // 返回文件URL
          } else if (data.code === 401) {
            handleUnauthorized(role, token)
            reject(data)
          } else {
            uni.showToast({ title: data.msg || '上传失败', icon: 'none' })
            reject(data)
          }
        } else if (res.statusCode === 401) {
          handleUnauthorized(role, token)
          reject(res)
        } else {
          reject(res)
        }
      },
      fail(err) {
        uni.showToast({ title: '上传失败', icon: 'none' })
        reject(err)
      }
    })
  })
}

// 快捷方法
export const get = (url, data, options = {}) => request({ url, method: 'GET', data, ...options })
export const post = (url, data, options = {}) => request({ url, method: 'POST', data, ...options })
export const put = (url, data, options = {}) => request({ url, method: 'PUT', data, ...options })
export const del = (url, data, options = {}) => request({ url, method: 'DELETE', data, ...options })
