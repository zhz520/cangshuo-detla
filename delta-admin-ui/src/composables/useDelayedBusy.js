import { ref, watch } from 'vue'

// Delay only the visual cue, never the operation or its duplicate-submit guard.
export function useDelayedBusy(source, delay = 160) {
  const visible = ref(false)
  watch(source, (busy, _, onCleanup) => {
    if (!busy) { visible.value = false; return }
    const timer = setTimeout(() => { visible.value = true }, delay)
    onCleanup(() => clearTimeout(timer))
  }, { immediate: true, flush: 'sync' })
  return visible
}
