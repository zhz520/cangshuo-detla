import { ref, onScopeDispose } from 'vue'

export function useReducedMotion() {
  // Non-web platforms get static progress and user-controlled swipers by default.
  const reduced = ref(true)
  // #ifdef H5
  const query = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  if (query) {
    const update = () => { reduced.value = query.matches }
    update()
    if (query.addEventListener) query.addEventListener('change', update)
    else query.addListener(update)
    onScopeDispose(() => {
      if (query.removeEventListener) query.removeEventListener('change', update)
      else query.removeListener(update)
    })
  }
  // #endif
  return reduced
}
