const H5_PREFIX = '/h5'

export const H5_ADMIN_PREFIX = H5_PREFIX

export function isH5Path(path = '') {
  return path === H5_PREFIX || path.startsWith(`${H5_PREFIX}/`)
}

export function h5Path(path = '/dashboard') {
  let value = String(path || '/dashboard')
  if (value === '/' || value === '') return H5_PREFIX
  if (isH5Path(value)) return value
  return H5_PREFIX + (value.startsWith('/') ? value : `/${value}`)
}

export function canonicalAdminPath(path = '/dashboard') {
  let value = String(path || '/dashboard')
  if (value === H5_PREFIX || value === `${H5_PREFIX}/`) return '/'
  if (value.startsWith(`${H5_PREFIX}/`)) value = value.slice(H5_PREFIX.length)
  return value
}

export function h5RedirectTarget(to, from) {
  if (!isH5Path(from?.path)) return null
  if (isH5Path(to?.path)) return null
  return {
    path: h5Path(to?.path || '/'),
    query: to?.query || {},
    hash: to?.hash || '',
  }
}

export function buildH5Routes(routes = [], layoutComponent) {
  const excluded = new Set(['/login', '/403', '/:pathMatch(.*)*'])
  return routes
    .filter(route => route?.children?.length && !excluded.has(route.path) && !route.meta?.mobileAlias)
    .map(route => {
      const clone = {
        ...route,
        path: h5Path(route.path),
        component: layoutComponent,
        redirect: typeof route.redirect === 'string' ? h5Path(route.redirect) : route.redirect,
        meta: { ...(route.meta || {}), mobileAlias: true, h5Alias: true },
        children: route.children.filter(child => !child.meta?.errorPage).map(child => ({
          ...child,
          ...(child.name ? { name: `H5${child.name}` } : {}),
          redirect: typeof child.redirect === 'string' ? h5Path(child.redirect) : child.redirect,
          meta: { ...(child.meta || {}), mobileAlias: true, h5Alias: true }
        }))
      }
      delete clone.name
      return clone
    })
}
