export function staffAdminUrl(base, path = '/mobile/login') {
  const origin = String(base || '').trim().replace(/\/+$/, '')
  if (!/^https:\/\/[^/?#@]+$/i.test(origin)) return ''
  const target = String(path || '/mobile/login')
  return origin + (target.startsWith('/') ? target : `/${target}`)
}

export function staffLoginUrl(base) {
  return staffAdminUrl(base, '/mobile/login')
}

export function staffHomeUrl(base) {
  return staffAdminUrl(base, '/mobile/dashboard')
}
