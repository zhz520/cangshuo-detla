import { getSiteConfig } from '@/api/config'
import { clientStorage } from '@/utils/client-storage'

const AGE_KEY = 'h5-adult-confirmed-until'
const VALID_MS = 30 * 86400000
let installed = false

// This is a storefront self-declaration, independent of identity verification.
export function mountAgeGate() {
  if (installed || typeof document === 'undefined') return
  const app = document.querySelector('#app')
  if (!app) return
  installed = true
  const gate = document.createElement('section')
  gate.className = 'age-gate'
  gate.hidden = true
  document.body.appendChild(gate)
  let memoryExpiry = 0, pending = false, ageEnabled, previousFocus
  function lock() {
    if (gate.hidden) previousFocus = document.activeElement
    app.inert = true
    app.setAttribute('aria-hidden', 'true')
    gate.hidden = false
  }
  function showLoading() {
    lock()
    gate.setAttribute('role', 'status')
    gate.setAttribute('aria-label', '正在加载')
    gate.removeAttribute('aria-modal')
    gate.removeAttribute('aria-labelledby')
    const spinner = document.createElement('span')
    spinner.className = 'age-gate-spinner'
    spinner.setAttribute('aria-hidden', 'true')
    gate.replaceChildren(spinner)
  }
  function show(title, buttons, state = 'confirm') {
    lock()
    gate.setAttribute('role', 'dialog')
    gate.setAttribute('aria-modal', 'true')
    gate.setAttribute('aria-labelledby', 'age-gate-title')
    gate.removeAttribute('aria-label')
    const panel = document.createElement('div')
    panel.className = 'age-gate-panel'
    panel.dataset.state = state
    const icon = document.createElement('span')
    icon.className = 'age-gate-icon'
    icon.setAttribute('aria-hidden', 'true')
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('viewBox', '0 0 24 24')
    svg.setAttribute('fill', 'none')
    svg.setAttribute('stroke', 'currentColor')
    svg.setAttribute('stroke-width', '1.65')
    svg.setAttribute('stroke-linecap', 'round')
    svg.setAttribute('stroke-linejoin', 'round')
    const shield = document.createElementNS(svg.namespaceURI, 'path')
    shield.setAttribute('d', 'M12 2.75 4.5 6v5.75c0 4.4 2.9 7.75 7.5 9.5 4.6-1.75 7.5-5.1 7.5-9.5V6L12 2.75Z')
    const mark = document.createElementNS(svg.namespaceURI, 'path')
    mark.setAttribute('d', state === 'confirm' ? 'm8.75 11.75 2.1 2.1 4.4-4.4' : 'M12 8v4.75m0 3h.01')
    svg.append(shield, mark)
    icon.append(svg)
    panel.append(icon)
    const heading = document.createElement('h1')
    heading.id = 'age-gate-title'
    heading.textContent = title
    heading.tabIndex = -1
    panel.appendChild(heading)
    const actions = document.createElement('div')
    actions.className = 'age-gate-actions'
    for (const [label, action, primary] of buttons) {
      const button = document.createElement('button')
      button.type = 'button'
      button.textContent = label
      button.className = primary ? 'age-gate-action age-gate-primary' : 'age-gate-action'
      button.addEventListener('click', action)
      actions.appendChild(button)
    }
    if (buttons.length) panel.appendChild(actions)
    gate.replaceChildren(panel)
    heading.focus({ preventScroll: true })
  }
  gate.addEventListener('keydown', (event) => {
    if (event.key !== 'Tab') return
    const actions = [...gate.querySelectorAll('.age-gate-action')]
    if (!actions.length) { event.preventDefault(); return }
    const last = actions[actions.length - 1]
    if (!actions.includes(document.activeElement) || (event.shiftKey ? document.activeElement === actions[0] : document.activeElement === last)) {
      event.preventDefault()
      const target = event.shiftKey ? last : actions[0]
      target.focus()
    }
  })
  function close() {
    if (gate.hidden) return
    gate.hidden = true
    app.inert = false
    app.removeAttribute('aria-hidden')
    if (previousFocus?.isConnected && previousFocus !== document.body) previousFocus.focus({ preventScroll: true })
    previousFocus = null
  }
  function accepted() {
    let saved = 0
    try { saved = Number(clientStorage.get(AGE_KEY)) || 0 } catch (_) {}
    return Math.max(memoryExpiry, saved) > Date.now()
  }
  function prompt() {
    show('您是否已满 18 岁？', [
      ['未满 18 岁', () => show('未满 18 岁暂不可进入', [['返回', prompt]], 'blocked')],
      ['已满 18 岁', () => {
        memoryExpiry = Date.now() + VALID_MS
        try { clientStorage.set(AGE_KEY, memoryExpiry) } catch (_) {}
        close()
      }, true]
    ])
  }
  async function load(first = false) {
    if (pending) return
    pending = true
    if (first) showLoading()
    try {
      const response = await getSiteConfig({ silent: true })
      ageEnabled = response.data?.age_confirmation_enabled === 'true'
      if (!ageEnabled || accepted()) close()
      else prompt()
    } catch (_) {
      if (ageEnabled === undefined) show('暂时无法加载，请重试', [['重新加载', () => load(true), true]], 'error')
    } finally { pending = false }
  }
  load(true)
  document.addEventListener('visibilitychange', () => { if (!document.hidden) load() })
}
