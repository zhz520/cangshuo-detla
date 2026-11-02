import test from 'node:test'
import assert from 'node:assert/strict'
import { createLatestTask } from '../src/utils/latest-task.js'

function fixture() {
  const pending = [], state = { value: null, loading: false, error: '' }
  const task = createLatestTask(query => new Promise((resolve, reject) => pending.push({ query, resolve, reject })), {
    start: () => { state.loading = true; state.error = '' }, success: value => { state.value = value },
    failure: error => { state.error = error.message }, finish: () => { state.loading = false }
  })
  return { pending, state, ...task }
}

test('slow old results cannot replace the latest search or stop its loading state', async () => {
  const f = fixture(), old = f.run('old'), latest = f.run('latest')
  f.pending[0].resolve('old results')
  await old
  assert.equal(f.state.value, null)
  assert.equal(f.state.loading, true)
  f.pending[1].resolve('latest results')
  await latest
  assert.equal(f.state.value, 'latest results')
  assert.equal(f.state.loading, false)
})

test('late failures do not hide a successful latest result', async () => {
  const f = fixture(), old = f.run('old'), latest = f.run('latest')
  f.pending[1].resolve('latest results')
  await latest
  f.pending[0].reject(new Error('old failure'))
  await old
  assert.equal(f.state.value, 'latest results')
  assert.equal(f.state.error, '')
})

test('errors retain successful data and retry clears the error', async () => {
  const f = fixture(), first = f.run()
  f.pending[0].resolve('existing')
  await first
  const failed = f.run()
  f.pending[1].reject(new Error('offline'))
  await failed
  assert.equal(f.state.value, 'existing')
  assert.equal(f.state.error, 'offline')
  const retry = f.run()
  assert.equal(f.state.error, '')
  f.pending[2].resolve('recovered')
  await retry
  assert.equal(f.state.value, 'recovered')
})

test('disposed views never publish late responses or start more work', async () => {
  const f = fixture(), first = f.run()
  f.dispose()
  f.pending[0].resolve('late data')
  await first
  await f.run()
  assert.equal(f.state.value, null)
  assert.equal(f.pending.length, 1)
})
