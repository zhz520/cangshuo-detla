export function resolveWebSocketUrl({ location, configured = '', runtime = '' } = {}) {
  // H5 packages are shared by customers: never bake a customer's host into them.
  if (location?.host) {
    if (/^wss?:\/\//i.test(runtime) && !(location.protocol === 'https:' && /^ws:/i.test(runtime))) return runtime
    return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/chat`
  }
  return /^wss?:\/\//i.test(configured) && !configured.includes('your-domain.example') ? configured : 'ws://localhost:8080/ws/chat'
}
