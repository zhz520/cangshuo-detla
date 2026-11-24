import { shallowReactive } from 'vue'
import { motionOwner, navigationKind } from './page-viewport.js'

// Guards/lazy chunks can race. Only a confirmed destination changes the entry
// direction. An abandoned guard must not clear a newer pending departure.
export function createNavigationMotion() {
  const state = shallowReactive({ pending: null, leaving: null, kind: 'shell', previous: null, position: null })
  function start(to, from) {
    state.pending = to
    state.leaving = from.matched.length ? motionOwner(to, from) : null
  }
  function settle(to, from, position, failed = false) {
    if (!failed) {
      const kind = navigationKind(to, from, position, state.position)
      if (kind !== 'same') state.kind = kind
      state.previous = state.position
      state.position = position
    }
    if (state.pending === to) {
      state.pending = null
      state.leaving = null
    }
  }
  return { state, start, settle }
}
export const navigationMotion = createNavigationMotion()
