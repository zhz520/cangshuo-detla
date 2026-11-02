// Each caller owns a completion handle, so an old request cannot hide a newer loader.
export function createLoadingController({ show, hide, delay = 180, schedule = setTimeout, cancel = clearTimeout }) {
  const active = new Map()
  let timer = null, visible = false, blocking = false
  function render() {
    const next = [...active.values()].some(Boolean)
    if (!visible || next !== blocking) { show({ title: '加载中…', mask: next }); blocking = next }
    visible = true
  }
  return {
    begin(mask = false) {
      const key = Symbol('loading')
      active.set(key, mask)
      if (visible) render()
      else if (timer === null) timer = schedule(() => { timer = null; if (active.size) render() }, delay)
      let finished = false
      return () => {
        if (finished) return
        finished = true
        active.delete(key)
        if (active.size) { if (visible) render(); return }
        if (timer !== null) { cancel(timer); timer = null }
        if (visible) hide()
        visible = false; blocking = false
      }
    }
  }
}
