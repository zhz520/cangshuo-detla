import { isH5Path } from './h5-route.js'

// The outer outlet owns shell changes only. The inner outlet owns page/parameter changes.
export function viewportKey(path, shell = false) {
  if (!shell) return path
  if (path === '/login' || path === '/h5/login') return path
  return isH5Path(path) ? 'h5-shell' : 'pc-shell'
}
