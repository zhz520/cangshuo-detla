const SHARED_NAME = 'delta-shared-media'
let generation = 0

function nextPaint(win) {
  // Rendering is paused during the View Transition update callback; rAF would
  // deadlock the DOM-update phase. Let Vue commit with a zero-delay task.
  return new Promise(resolve => win.setTimeout(resolve, 0))
}

/** Morph the clicked media element into the destination page element. */
export function navigateWithSharedMedia({ source, targetSelector, navigate, doc = document, win = window }) {
  const current = ++generation
  const start = doc.startViewTransition?.bind(doc)
  if (!start || !source?.style || win.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
    return navigate()
  }
  const root = doc.documentElement
  const sourceName = source.style.viewTransitionName
  let target
  let targetName = ''
  let started = false
  const run = () => {
    if (!started) { started = true; return navigate() }
    return Promise.resolve()
  }
  const cleanup = () => {
    if (current !== generation) return
    if (source.style.viewTransitionName === SHARED_NAME) source.style.viewTransitionName = sourceName || ''
    if (target && target.style.viewTransitionName === SHARED_NAME) target.style.viewTransitionName = targetName
    delete root.dataset.deltaView
    target = null
  }
  source.style.viewTransitionName = SHARED_NAME
  root.dataset.deltaView = 'shared'
  let transition
  try {
    transition = start(async () => {
      const result = await run()
      for (let i = 0; i < 30; i += 1) {
        target = doc.querySelector(targetSelector)
        if (target) break
        await new Promise(resolve => win.setTimeout(resolve, 16))
      }
      if (target) {
        targetName = target.style.viewTransitionName
        target.style.viewTransitionName = SHARED_NAME
      }
      await nextPaint(win)
      return result
    })
  } catch (_) {
    cleanup()
    return run()
  }
  transition.finished.then(cleanup, cleanup)
  return transition.updateCallbackDone
}

