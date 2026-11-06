import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, removeToken } from './auth'
import router from '@/router'
import { createRequestClient } from './request-client'
import { loadingFeedback } from './loading-feedback'
import { paidRequestAccess } from './feature-access-policy'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000
})

function reportError(config, message, code) {
  if (config?.silent) return
  if ((config?.method || 'get').toLowerCase() === 'get' && ![401, 403].includes(code)) {
    if (config?._pagePath === router.currentRoute.value.fullPath) {
      loadingFeedback.readError = { path: config._pagePath, message }
    }
    return
  }
  ElMessage.error({ message, grouping: true })
}

// 请求拦截器 - 注入 token
service.interceptors.request.use(
  (config) => {
    const token = getToken()
    config._authToken = token
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 0 && res.code !== 200) {
      reportError(response.config, res.msg || '请求失败', res.code)
      // token 过期或无效
      if (res.code === 401 && response.config._authToken === getToken()) {
        removeToken()
        router.push('/login')
      }
      return Promise.reject(Object.assign(new Error(res.msg || '请求失败'), { code: res.code, requestHandled: true }))
    }
    return res
  },
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error)
    const notify = (type, message) => {
      if (!error.config?.silent) ElMessage({ type, message, grouping: true })
    }
    if (error.response) {
      const { status } = error.response
      if (status === 401) {
        if (error.config?._authToken !== getToken()) return Promise.reject(error)
        removeToken()
        router.push('/login')
        notify('error', '登录已过期，请重新登录')
      } else if (status === 503 && error.response.data?.maintenance) {
        notify('warning', error.response.data.msg)
        if (!router.currentRoute.value.path.startsWith('/platform/')) router.push('/platform/updates')
      } else if (status === 403) {
        notify('error', '没有权限执行此操作')
      } else {
        reportError(error.config, error.response.data?.msg || '服务器错误', status)
      }
    } else {
      reportError(error.config, error.code === 'ECONNABORTED' ? '请求超时，请重试' : '网络连接异常，请重试')
    }
    error.requestHandled = true
    return Promise.reject(error)
  }
)

async function sendRequest(config) {
  const access = paidRequestAccess(config.url)
  if (access) {
    try {
      const { requirePlatformFeature } = await import('./platform-feature-visibility')
      await requirePlatformFeature(access.feature, access.permission)
    } catch (error) { reportError(config, error.message, error.code); throw error }
  }
  return service(config)
}
const request = createRequestClient(config => sendRequest({ ...config, _pagePath: router.currentRoute.value.fullPath }), config => JSON.stringify([
  getToken(), service.getUri(config), config.headers || {}, config.responseType || 'json',
  config.timeout ?? service.defaults.timeout, Boolean(config.silent), config.withCredentials, config.auth, config.data,
  router.currentRoute.value.fullPath
]), {
  start: () => { loadingFeedback.requests++ },
  finish: () => { loadingFeedback.requests-- }
})

export default request
