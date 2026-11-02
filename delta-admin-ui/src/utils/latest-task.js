// Only the latest invocation may publish data/error/loading changes.
export function createLatestTask(task, { start, success, failure, finish }) {
  let sequence = 0
  let disposed = false
  return {
    async run(...args) {
      if (disposed) return
      const current = ++sequence
      start()
      try {
        const value = await task(...args)
        if (!disposed && current === sequence) success(value)
      } catch (error) {
        if (!disposed && current === sequence) failure(error)
      } finally {
        if (!disposed && current === sequence) finish()
      }
    },
    dispose() { disposed = true; sequence++ }
  }
}
