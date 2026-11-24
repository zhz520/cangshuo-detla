const CLASS = 'tabbar-page-locked'
let holds = 0
let releaseTimer

function setLocked(doc, locked) {
  if (!doc?.documentElement || !doc.body) return
  doc.documentElement.classList.toggle(CLASS, locked)
  doc.body.classList.toggle(CLASS, locked)
}

/** Keep the page locked across the old/new tabbar hand-off. */
export function holdTabbarScrollLock(doc = document, win = window) {
  holds += 1
  if (releaseTimer != null) {
    win.clearTimeout(releaseTimer)
    releaseTimer = null
  }
  setLocked(doc, true)
}

export function releaseTabbarScrollLock(doc = document, win = window) {
  holds = Math.max(0, holds - 1)
  if (releaseTimer != null) win.clearTimeout(releaseTimer)
  releaseTimer = win.setTimeout(() => {
    releaseTimer = null
    if (holds === 0) setLocked(doc, false)
  }, 180)
}

export function resetTabbarScrollLock(doc = document, win = window) {
  holds = 0
  if (releaseTimer != null) win.clearTimeout(releaseTimer)
  releaseTimer = null
  setLocked(doc, false)
}
