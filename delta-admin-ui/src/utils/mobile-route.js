const MOBILE_PREFIX = '/mobile'

export const MOBILE_ADMIN_PREFIX = MOBILE_PREFIX

export function isMobileAdminPath(path = '') {
  return path === MOBILE_PREFIX || path.startsWith(`${MOBILE_PREFIX}/`)
}

export function mobileAdminPath(path = '/dashboard') {
  const value = String(path || '/dashboard')
  if (isMobileAdminPath(value)) return value
  if (value === '/' || value === '') return MOBILE_PREFIX
  return `${MOBILE_PREFIX}${value.startsWith('/') ? '' : '/'}${value}`
}

export function canonicalAdminPath(path = '/dashboard') {
  const value = String(path || '/dashboard')
  if (value === MOBILE_PREFIX || value === `${MOBILE_PREFIX}/`) return '/'
  if (value.startsWith(`${MOBILE_PREFIX}/`)) return value.slice(MOBILE_PREFIX.length)
  return value
}

export function shouldUseMobileRoute(path, isMobileViewport) {
  if (!isMobileViewport) return false
  if (!path || path === '/403') return false
  if (path === '/login' || path === '/mobile/login' || isMobileAdminPath(path)) return false
  return true
}

export function adminLoginPath(isMobileViewport) {
  return isMobileViewport ? `${MOBILE_PREFIX}/login` : '/login'
}

export function buildMobileAdminRoutes(routes = []) {
  const excluded = new Set(['/login', '/403', '/:pathMatch(.*)*'])
  const cloned = routes
    .filter(route => route?.children?.length && !excluded.has(route.path) && !route.meta?.mobileAlias)
    .map(route => {
      const clone = {
        ...route,
        path: mobileAdminPath(route.path),
        redirect: typeof route.redirect === 'string' ? mobileAdminPath(route.redirect) : route.redirect,
        meta: { ...(route.meta || {}), mobileAlias: true },
        children: route.children.map(child => ({
          ...child,
          ...(child.name ? { name: `Mobile${child.name}` } : {}),
          redirect: typeof child.redirect === 'string' ? mobileAdminPath(child.redirect) : child.redirect,
          meta: { ...(child.meta || {}), mobileAlias: true }
        }))
      }
      delete clone.name
      return clone
    })
  cloned.push({
    path: `${MOBILE_PREFIX}/:pathMatch(.*)*`,
    name: 'MobileNotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', mobileAlias: true }
  })
  return cloned
}
