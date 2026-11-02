import test from 'node:test'
import assert from 'node:assert/strict'
import { setImmediate } from 'node:timers/promises'
import { usePagedList } from '../composables/usePagedList.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function harness(options = {}) {
  const calls = []
  const list = usePagedList({ pageSize: 2, ...options, fetchPage(params) {
    const task = deferred()
    calls.push({ ...task, params })
    return task.promise
  } })
  return { calls, list }
}

const data = (ids, total) => ({ records: ids.map(id => ({ id })), ...(total === undefined ? {} : { total }) })
const ids = list => list.items.value.map(item => item.id)
async function answer(calls, index, response) {
  await setImmediate()
  assert.ok(calls[index], `request ${index} must exist`)
  calls[index].resolve(response)
  await setImmediate()
}

test('duplicate refresh, retry and bottom events share one initial request', async () => {
  const { list, calls } = harness()
  const first = list.refresh()
  assert.equal(list.loading.value, true)
  assert.equal(list.refresh(), first)
  assert.equal(list.retry(), first)
  assert.equal(list.loadMore(), first)
  await answer(calls, 0, data([1, 2], 4))
  assert.equal(await first, true)
  assert.equal(calls.length, 1)
  assert.equal(list.pageNum.value, 1)
  assert.equal(list.loading.value, false)
  assert.equal(list.finished.value, false)
  await list.setQuery('')
  assert.equal(calls.length, 1, 'clicking the selected filter must not refresh')
})

test('initial failure settles loading and retry requests page one', async () => {
  const { list, calls } = harness()
  const first = list.refresh()
  await setImmediate()
  calls[0].reject({ msg: '服务暂不可用' })
  assert.equal(await first, false)
  assert.equal(list.error.value, '服务暂不可用')
  assert.equal(list.loading.value, false)
  assert.equal(list.pageNum.value, 0)
  assert.deepEqual(ids(list), [])
  await list.loadMore()
  assert.equal(calls.length, 1)
  const retry = list.retry()
  assert.equal(list.retry(), retry)
  await answer(calls, 1, data([], 0))
  assert.equal(await retry, true)
  assert.equal(calls[1].params.pageNum, 1)
  assert.equal(list.error.value, '')
  assert.equal(list.finished.value, true)
})

test('failed pagination preserves rows and retries exactly the failed page', async () => {
  const { list, calls } = harness()
  const initial = list.refresh()
  await answer(calls, 0, data([1, 2], 6))
  await initial
  const next = list.loadMore()
  assert.equal(list.loadMore(), next)
  await setImmediate()
  calls[1].reject({ errMsg: 'request:fail timeout' })
  await next
  assert.equal(list.loading.value, false)
  assert.equal(list.error.value, '加载失败，请重试')
  assert.equal(list.pageNum.value, 1)
  assert.deepEqual(ids(list), [1, 2])
  await list.loadMore()
  assert.equal(calls.length, 2, 'scroll events must not create a retry loop')
  const retry = list.retry()
  await answer(calls, 2, data([3, 4], 6))
  await retry
  assert.deepEqual(calls.map(call => call.params.pageNum), [1, 2, 2])
  assert.deepEqual(ids(list), [1, 2, 3, 4])
})

for (const outcome of ['success', 'failure']) {
  test(`a stale ${outcome} cannot alter the selected filter, loading or error`, async () => {
    const { list, calls } = harness()
    const old = list.refresh()
    await setImmediate()
    const current = list.setQuery('IN_PROGRESS')
    await setImmediate()
    if (outcome === 'success') calls[0].resolve(data([1]))
    else calls[0].reject(new Error('旧请求失败'))
    await old
    assert.equal(list.loading.value, true)
    assert.equal(list.error.value, '')
    assert.deepEqual(ids(list), [])
    assert.equal(calls[1].params.query, 'IN_PROGRESS')
    await answer(calls, 1, data([9]))
    await current
    assert.deepEqual(ids(list), [9])
  })
}

test('A to B to A waits out the first A and ignores late B', async () => {
  const { list, calls } = harness({ initialQuery: 'A' })
  const oldA = list.refresh()
  await setImmediate()
  const oldB = list.setQuery('B')
  await setImmediate()
  const newA = list.setQuery('A')
  await setImmediate()
  assert.equal(calls.length, 2)
  await answer(calls, 0, data([1]))
  await oldA
  assert.equal(calls[2].params.query, 'A')
  await answer(calls, 2, data([3]))
  await newA
  await answer(calls, 1, data([2]))
  await oldB
  assert.deepEqual(ids(list), [3])
})

test('overlapping pages update repeated ids once without prematurely ending pagination', async () => {
  const { list, calls } = harness()
  const initial = list.refresh()
  await answer(calls, 0, data([1, 2]))
  await initial
  const next = list.loadMore()
  await answer(calls, 1, { records: [{ id: '2', status: 'new' }, { id: 3 }] })
  await next
  assert.deepEqual(ids(list), [1, '2', 3])
  assert.equal(list.items.value[1].status, 'new')
  assert.equal(list.pageNum.value, 2)
  assert.equal(list.finished.value, false, 'use raw page length, not deduplicated count')
})

test('total stops an exactly full final page without an extra empty request', async () => {
  const { list, calls } = harness()
  const first = list.refresh()
  await answer(calls, 0, data([1, 2], '2'))
  await first
  assert.equal(list.finished.value, true)
  await list.loadMore()
  assert.equal(calls.length, 1)
})

test('missing or invalid total falls back to raw page length', async () => {
  for (const total of [undefined, null, '', 'unknown', -1]) {
    const { list, calls } = harness()
    const first = list.refresh()
    await answer(calls, 0, data([1, 2], total))
    await first
    assert.equal(list.finished.value, false)
    const next = list.loadMore()
    await answer(calls, 1, data([3], total))
    await next
    assert.equal(list.finished.value, true)
  }
})

test('return refresh keeps all loaded pages until atomic success and preserves them on partial failure', async () => {
  const { list, calls } = harness()
  let request = list.refresh()
  await answer(calls, 0, data([1, 2], 6))
  await request
  request = list.loadMore()
  await answer(calls, 1, data([3, 4], 6))
  await request
  const previous = list.items.value
  request = list.refresh()
  assert.equal(list.items.value, previous)
  await answer(calls, 2, data([10, 20], 6))
  assert.equal(list.items.value, previous)
  assert.equal(list.pageNum.value, 2)
  calls[3].reject(new Error('第二页失败'))
  await request
  assert.equal(list.items.value, previous)
  assert.equal(list.pageNum.value, 2)
  request = list.retry()
  await answer(calls, 4, data([10, 20], 6))
  await answer(calls, 5, data([30, 40], 6))
  await request
  assert.deepEqual(ids(list), [10, 20, 30, 40])
  request = list.loadMore()
  await answer(calls, 6, data([50, 60], 6))
  await request
  assert.deepEqual(calls.map(call => call.params.pageNum), [1, 2, 1, 2, 1, 2, 3])
})

test('refresh stops early if orders disappear while viewing a detail page', async () => {
  const { list, calls } = harness()
  let request = list.refresh()
  await answer(calls, 0, data([1, 2], 4))
  await request
  request = list.loadMore()
  await answer(calls, 1, data([3, 4], 4))
  await request
  request = list.refresh()
  await answer(calls, 2, data([2], 1))
  await request
  assert.deepEqual(ids(list), [2])
  assert.equal(list.pageNum.value, 1)
  assert.equal(list.finished.value, true)
  assert.equal(calls.length, 3)
})

test('refresh supersedes an append and discards its late response', async () => {
  const { list, calls } = harness()
  const initial = list.refresh()
  await answer(calls, 0, data([1, 2], 6))
  await initial
  const append = list.loadMore()
  await setImmediate()
  const refresh = list.refresh()
  assert.equal(list.loadMore(), refresh)
  await answer(calls, 1, data([3, 4], 6))
  await append
  assert.deepEqual(ids(list), [1, 2])
  assert.equal(list.loading.value, true)
  await answer(calls, 2, data([8, 9], 6))
  await refresh
  assert.deepEqual(ids(list), [8, 9])
  assert.equal(list.pageNum.value, 1)
})

test('hide and show retain content and issue a fresh read after the invalidated pending GET', async () => {
  const { list, calls } = harness()
  const initial = list.refresh()
  await answer(calls, 0, data([1, 2], 4))
  await initial
  const old = list.refresh()
  await setImmediate()
  list.cancel()
  const current = list.refresh()
  await setImmediate()
  assert.equal(calls.length, 2)
  await answer(calls, 1, data([3, 4], 4))
  await old
  assert.deepEqual(ids(list), [1, 2])
  await answer(calls, 2, data([5, 6], 4))
  await current
  assert.deepEqual(ids(list), [5, 6])
})

test('forced refresh after acceptance does not reuse a pre-acceptance GET', async () => {
  const { list, calls } = harness()
  const before = list.refresh()
  await setImmediate()
  const after = list.refresh({ force: true })
  assert.equal(list.refresh(), after)
  await setImmediate()
  assert.equal(calls.length, 1)
  await answer(calls, 0, data([1, 2]))
  await before
  assert.deepEqual(ids(list), [])
  await answer(calls, 1, data([2]))
  await after
  assert.deepEqual(ids(list), [2])
})

test('unload ignores late errors and prohibits later requests', async () => {
  const { list, calls } = harness()
  const pending = list.refresh()
  await setImmediate()
  list.dispose()
  calls[0].reject(new Error('late failure'))
  await pending
  await list.refresh()
  await list.retry()
  await list.loadMore()
  await list.setQuery('another')
  assert.equal(calls.length, 1)
  assert.equal(list.loading.value, false)
  assert.equal(list.error.value, '')
  assert.deepEqual(ids(list), [])
})

test('invalid response and synchronous failures produce retryable errors', async () => {
  const { list, calls } = harness()
  let request = list.refresh()
  await answer(calls, 0, {})
  await request
  assert.equal(list.error.value, '订单数据异常，请重试')
  assert.equal(list.loading.value, false)
  request = list.retry()
  await answer(calls, 1, data([]))
  await request
  assert.equal(list.error.value, '')
  const sync = usePagedList({ fetchPage() { throw new Error('同步失败') } })
  assert.equal(await sync.refresh(), false)
  assert.equal(sync.error.value, '同步失败')
  assert.equal(sync.loading.value, false)
})
