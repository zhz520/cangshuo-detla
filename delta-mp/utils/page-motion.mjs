/** Observe successful uni H5 router commits; never key, remount or scroll the page. */
export function routeMotion(to, from, position, previous) {
  if (!from?.matched?.length) return 'enter'
  if (position != null && previous != null && position < previous) return 'back'
  return to.meta.isTabBar || to.meta.isQuit ? 'tab' : 'enter'
}

export function installPageMotion(router, doc = document, win = window) {
  let target, observer, frame, timeout, previous, last
  const layers = []
  const stopFinding = () => {
    observer?.disconnect()
    win.clearTimeout(timeout)
    observer = timeout = null
  }
  const clear = () => {
    if (frame != null) win.cancelAnimationFrame(frame)
    stopFinding()
    for (const layer of layers.splice(0)) layer.removeAttribute('data-delta-layer')
    target?.removeAttribute('data-delta-enter')
    frame = target = null
  }
  function committed(to, from, failure) {
    if (failure || !to?.meta?.route) return
    const position = win.history.state?.position
    const key = [to.fullPath, win.history.state?.id, position].join('|')
    // replace() of a query/filter on the same history entry is not a navigation.
    if (last === key || (last && to.path === from?.path && position === previous && to.path)) return
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
      target = Array.from(body.querySelectorAll('[data-delta-content]')).find(node => node.getClientRects().length) || body
      const motion = kind !== 'back' && target.dataset.deltaContent === 'tab' ? 'tab' : kind
      target.setAttribute('data-delta-enter', motion)
      // Never transform a page-sized ancestor of fixed checkout/chat controls. For
      // unmarked screens, move visible first-level cards, not the viewport itself.
      if (target === body) {
        for (const el of body.firstElementChild.children) {
          if (layers.length >= 4) break
          if (!el.matches('.card,.section,.form-card,.status-card,.status-header,.amount-area,.pay-methods,.balance-card,.summary-card,.hero,.lookup-card,.rating-area') || !el.getClientRects().length) continue
          el.setAttribute('data-delta-layer', motion)
          layers.push(el)
        }
      }
      stopFinding()
      return true
    }
    // Async routes and cached pages have different mount timing. One mark per commit, not per update.
    frame = win.requestAnimationFrame(() => {
      if (!find()) {
        observer = new win.MutationObserver(find)
        observer.observe(doc.body, {childList: true, subtree: true, attributes: true, attributeFilter: ['style','class','hidden','data-page']})
        timeout = win.setTimeout(stopFinding, 2500)
      }
    })
  }
  const stop = router.afterEach(committed)
  committed(router.currentRoute.value)
  return () => { stop(); clear() }
}
