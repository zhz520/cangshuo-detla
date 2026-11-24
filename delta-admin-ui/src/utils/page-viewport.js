import { canonicalAdminPath, isH5Path } from './h5-route.js'

// Render ownership comes from the resolved router records, not a list of login URLs.
// Query/hash/history position are deliberately never component keys.
export function viewportKey(route, shell = false) {
  const path = route.path.replace(/\/$/, '') || '/'
  if (!shell || route.matched.length < 2) return path
  return isH5Path(path) ? 'h5-shell' : 'pc-shell'
}

export function motionOwner(to, from) {
  if (!from.matched.length || viewportKey(to, true) !== viewportKey(from, true)) return 'shell'
  return viewportKey(to) === viewportKey(from) ? null : 'page'
}

export function navigationKind(to, from, position, previous) {
  const owner = motionOwner(to, from)
  if (!owner) return 'same'
  if (from.matched.length && Number.isInteger(position) && Number.isInteger(previous) && position < previous) return 'back'
  if (owner === 'shell') return 'shell'
  const path = canonicalAdminPath(to.path), origin = canonicalAdminPath(from.path)
  if (from.meta.activeMenu === path || (path === '/dashboard' && position === previous)) return 'back'
  if (to.meta.activeMenu || (Object.keys(to.params).some(key => key !== 'pathMatch') && origin !== path)) return 'forward'
  return 'tab'
}
