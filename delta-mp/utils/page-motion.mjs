/** Observe successful uni H5 router commits; never key, remount or scroll the page. */
export function routeMotion(to, from, position, previous) {
  if (!from?.matched?.length) return 'enter'
  if (position != null && previous != null && position < previous) return 'back'
  return to.meta.isTabBar || to.meta.isQuit ? 'tab' : 'enter'
}

export function installPageMotion(router, doc = document, win = window) {
  let target, observer, frame, previous, last
  const clear = () => {
    if (frame != null) win.cancelAnimationFrame(frame)
    observer?.disconnect()
    target?.removeAttribute('data-delta-enter')
    frame = observer = target = null
  }
  function committed(to, from, failure) {
    if (failure || !to?.meta?.route) return
    const position = win.history.state?.position
    const key = [to.fullPath, win.history.state?.id, position].join('|')
    if (last === key) return
    last = key
    clear()
    const kind = routeMotion(to, from, position, previous)
    previous = position
    if (win.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
    const find = () => {
      const page = Array.from(doc.querySelectorAll('uni-page[data-page]')).reverse()
        .find(node => node.dataset.page === to.meta.route && node.getClientRects().length)
      const body = page?.querySelector('uni-page-body')
      if (!body || !body.firstElementChild) return false
      target = body.querySelector('[data-delta-content]') || body
      target.setAttribute('data-delta-enter', target.dataset.deltaContent === 'tab' ? 'tab' : kind)
      observer?.disconnect()
      return true
    }
    // Async routes and cached pages have different mount timing. One mark per commit, not per update.
    frame = win.requestAnimationFrame(() => {
      if (!find()) {
        observer = new win.MutationObserver(find)
        observer.observe(doc.body, {childList: true, subtree: true})
      }
    })
  }
  const stop = router.afterEach(committed)
  committed(router.currentRoute.value)
  return () => { stop(); clear() }
}
