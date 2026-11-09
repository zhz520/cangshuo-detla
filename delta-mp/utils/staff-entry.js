export function staffAdminUrl(base, path = '/h5/login') {
  const origin = String(base || '').trim().replace(/\/+$/, '')
  if (!/^https:\/\/[^/?#@]+$/i.test(origin)) return ''
  const target = String(path || '/h5/login')
  return origin + (target.startsWith('/') ? target : `/${target}`)
}

export function staffLoginUrl(base) {
  return staffAdminUrl(base, '/h5/login')
}

export function staffHomeUrl(base) {
  return staffAdminUrl(base, '/h5/dashboard')
}
