let pending = null

export function rememberTabMotion(from, direction) {
  if (!Number.isInteger(from) || from < 0) return
  pending = { from, direction: direction === 'back' ? 'back' : 'forward', at: Date.now() }
}

export function takeTabMotion() {
  const value = pending
  pending = null
  if (!value || Date.now() - value.at > 3000) return null
  return value
}

