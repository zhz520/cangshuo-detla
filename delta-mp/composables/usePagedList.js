import { readonly, ref } from 'vue'

// Only the player hall and work list use this state machine. fetchPage returns { records, total? }.
export function usePagedList({ fetchPage, initialQuery = '', pageSize = 10 }) {
  const query = ref(initialQuery)
  const items = ref([])
  const pageNum = ref(0) // Last successfully committed page, never the requested page.
  const loading = ref(false)
  const error = ref('')
  const finished = ref(false)
  let version = 0
  let active = null
  const pendingByQuery = new Map()
  let failedKind = null
  let disposed = false

  function run(kind, force = false) {
    if (disposed) return Promise.resolve(false)
    // Refresh supersedes pagination; repeated refresh/retry/scroll events share one request.
    if (active && !force && (kind === 'append' || active.kind === 'refresh')) return active.promise

    const task = { kind, version: ++version, query: query.value }
    const firstPage = kind === 'append' ? pageNum.value + 1 : 1
    const lastPage = kind === 'append' ? firstPage : Math.max(1, pageNum.value)
    const previous = kind === 'append' ? items.value : []
    const preceding = pendingByQuery.get(task.query)
    const isCurrent = () => !disposed && task.version === version
    active = task
    loading.value = true
    error.value = ''
    failedKind = null

    task.promise = Promise.resolve().then(async () => {
      try {
        // The API coalesces pending GETs. After a mutation or hide/show, wait for the
        // invalidated read before issuing a fresh one for the same filter.
        if (preceding) await preceding
        let records = []
        let completedPage = firstPage
        let atEnd = false
        for (let page = firstPage; page <= lastPage; page++) {
          if (!isCurrent()) return false
          const data = await fetchPage({ pageNum: page, pageSize, query: task.query, isCurrent })
          if (!isCurrent()) return false
          if (!Array.isArray(data?.records)) throw new Error('订单数据异常，请重试')
          records = records.concat(data.records)
          completedPage = page
          const total = data.total == null || data.total === '' ? NaN : Number(data.total)
          atEnd = data.records.length < pageSize || (Number.isFinite(total) && total >= 0 && page * pageSize >= total)
          if (atEnd) break
        }
        // Refresh all previously loaded pages atomically, keeping the scroll-view and its content
        // mounted while returning from a detail page. A partial refresh never truncates the list.
        items.value = mergeRecords(previous, records)
        pageNum.value = completedPage
        finished.value = atEnd
        return true
      } catch (err) {
        if (isCurrent()) {
          error.value = String(err?.msg || err?.message || '加载失败，请重试')
          failedKind = kind
        }
        return false
      } finally {
        if (pendingByQuery.get(task.query) === task.promise) pendingByQuery.delete(task.query)
        if (isCurrent()) {
          loading.value = false
          active = null
        }
      }
    })
    pendingByQuery.set(task.query, task.promise)
    return task.promise
  }

  function refresh({ force = false } = {}) {
    return run('refresh', force)
  }

  function loadMore() {
    if (active) return active.promise
    // Do not let scrolltolower silently retry a failed page in a loop.
    if (error.value || finished.value || !pageNum.value) return Promise.resolve(false)
    return run('append')
  }

  function retry() {
    if (active) return active.promise
    return failedKind ? run(failedKind) : Promise.resolve(false)
  }

  function cancel() {
    version++
    active = null
    loading.value = false
  }

  function setQuery(value) {
    if (disposed || value === query.value) return active?.promise || Promise.resolve(false)
    cancel()
    query.value = value
    items.value = []
    pageNum.value = 0
    finished.value = false
    error.value = ''
    failedKind = null
    return refresh()
  }

  function dispose() {
    cancel()
    disposed = true
  }

  return { query: readonly(query), items, pageNum, loading, error, finished, refresh, loadMore, retry, setQuery, cancel, dispose }
}

function mergeRecords(previous, incoming) {
  const result = []
  const positions = new Map()
  for (const record of [...previous, ...incoming]) {
    const id = record.id == null ? null : String(record.id)
    if (id !== null && positions.has(id)) result[positions.get(id)] = record
    else {
      if (id !== null) positions.set(id, result.length)
      result.push(record)
    }
  }
  return result
}
