/** Observe successful uni H5 router commits; never key, remount or scroll the page. */
export function routeMotion(to, from, position, previous) {
  if (!from?.matched?.length) return 'enter'
  if (position != null && previous != null && position < previous) return 'back'
  return to.meta.isTabBar || to.meta.isQuit ? 'tab' : 'enter'
}

const TAB_PAGES = [
  '/pages/index/index',
  '/pages/category/index',
  '/pages/players/index',
  '/pages/chat/list',
  '/pages/mine/index'
]
let transitionGeneration = 0

function nextPaint(win) {
  // Rendering is paused while a View Transition update callback runs. rAF would
  // deadlock here; a timer lets Vue commit the new page before the snapshot.
  return new Promise(resolve => win.setTimeout(resolve, 0))
}

/**
 * Keep the old screen on screen until the destination has mounted. Chromium's
 * View Transition snapshot prevents the white gap created when UniApp replaces
 * a tab page. The page-motion fallback stays in place for older WebViews.
 */
export function navigateTabWithTransition(navigate, doc = document, win = window) {
  const start = doc.startViewTransition?.bind(doc)
  let startedNavigation = false, navigation
  const run = () => {
    if (!startedNavigation) {
      startedNavigation = true
      navigation = Promise.resolve(navigate())
    }
    return navigation
  }
  if (!start || doc.visibilityState === 'hidden' || win.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
    return { started: false, settled: run() }
  }
  const generation = ++transitionGeneration
  const root = doc.documentElement
  root.dataset.deltaView = 'tab'
  let transition
  try {
    transition = start(() => {
      // A broken completion callback must not freeze the old frame forever.
      return Promise.race([run(), new Promise(resolve => win.setTimeout(resolve, 1500))])
        .then(() => nextPaint(win), () => nextPaint(win))
    })
  } catch (_) {
    delete root.dataset.deltaView
    return { started: false, settled: run() }
  }
  const clear = () => { if (generation === transitionGeneration) delete root.dataset.deltaView }
  transition.finished.then(clear, clear)
  return { started: true, settled: transition.updateCallbackDone }
}

/** Only fetch route chunks; do not create page instances or call page APIs. */
export function preloadTabPages(win = window) {
  if (typeof uni === 'undefined' || typeof uni.preloadPage !== 'function') return () => {}
  let timer
  const run = () => {
    for (const url of TAB_PAGES) {
      try { uni.preloadPage({ url, fail: () => {} }) } catch (_) {}
    }
  }
  timer = win.requestIdleCallback ? win.requestIdleCallback(run, { timeout: 2500 }) : win.setTimeout(run, 900)
  return () => {
    if (win.cancelIdleCallback) win.cancelIdleCallback(timer)
    else win.clearTimeout(timer)
  }
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
    // The View Transition owns the whole visual state for this navigation.
    if (doc.documentElement.dataset.deltaView) {
      previous = position
      return
    }
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
  preloadTabPages(win)
  return () => { stop(); clear() }
}
