import { nextTick, watch } from 'vue'
import { navigationMotion } from '../utils/navigation-motion.js'

// Scoped to one authenticated layout. Store positions, never form values or component instances.
export function useRouteSurface(route, element) {
  const entries = new Map()
  const motion = navigationMotion.state
  watch(() => route.path, async (path, from, onCleanup) => {
    const main = element()
    if (!main) return
    if (Number.isInteger(motion.previous)) {
      entries.set(motion.previous, { path: from, top: main.scrollTop, left: main.scrollLeft,
        focusId: main.contains(document.activeElement) ? document.activeElement.id : '' })
      if (entries.size > 60) entries.delete(entries.keys().next().value)
    }
    const saved = motion.position < motion.previous && entries.get(motion.position)
    const target = saved && saved.path === path ? saved : null
    let cancelled = false, observer, timer
    function stop() {
      cancelled = true
      observer?.disconnect()
      clearTimeout(timer)
      for (const event of ['wheel','pointerdown','keydown']) main.removeEventListener(event, stop)
    }
    onCleanup(stop)
    await nextTick()
    if (cancelled || !main.isConnected) return
    const focus = target?.focusId && document.getElementById(target.focusId)
    const focusTarget = focus && main.contains(focus) ? focus : main
    focusTarget.focus({ preventScroll: true })
    function restore() {
      main.scrollLeft = target?.left ?? 0
      main.scrollTop = target?.top ?? 0
      if (!target || Math.abs(main.scrollTop - target.top) < 1) stop()
    }
    restore()
    // A list can initially render a short loading state. Retry as it grows,
    // but yield immediately to user interaction or the next navigation.
    const frame = main.querySelector('.layout-page-frame')
    if (!cancelled && frame && typeof ResizeObserver === 'function') {
      observer = new ResizeObserver(restore)
      observer.observe(frame)
      for (const event of ['wheel','pointerdown','keydown']) main.addEventListener(event, stop, { passive: true })
      timer = setTimeout(stop, 1500)
    }
  })
}
