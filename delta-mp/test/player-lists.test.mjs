import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setImmediate } from 'node:timers/promises'
import { ref } from 'vue'
import { babelParse, compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import { usePagedList } from '../composables/usePagedList.js'
import { PLAYER_ORDER_TABS } from '../utils/constants.js'

const response = (ids = [], total = ids.length) => ({ data: { records: ids.map(id => ({ id })), total } })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

// Compile the actual SFC setup and template. Replace imports with small in-memory app/API
// adapters so Node exercises page lifecycle wiring without a browser or a logged-in account.
function pageHarness(kind, overrides = {}) {
  const filename = kind === 'hall' ? 'pages-player/hall/index.vue' : 'pages-player/order/list.vue'
  const { descriptor, errors } = parse(readFileSync(new URL(`../${filename}`, import.meta.url), 'utf8'), { filename })
  assert.deepEqual(errors, [])
  const compiled = compileScript(descriptor, { id: kind, genDefaultAs: '__page' })
  const template = compileTemplate({
    source: descriptor.template.content, filename, id: kind,
    compilerOptions: { bindingMetadata: compiled.bindings }
  })
  assert.deepEqual(template.errors, [])
  const hooks = Object.fromEntries(['onShow', 'onHide', 'onUnload', 'onPullDownRefresh'].map(name => [name, []]))
  let stopped = 0
  const modules = {
    vue: { ref },
    '@dcloudio/uni-app': Object.fromEntries(Object.entries(hooks).map(([name, callbacks]) => [name, fn => callbacks.push(fn)])),
    '@/composables/usePagedList': { usePagedList },
    '@/api/player': { getMyWork: async () => response(), getOrderHall: async () => response(), ...overrides.player },
    '@/api/category': { getAllCategories: async () => ({ data: [] }), ...overrides.category },
    '@/utils/constants': { PLAYER_ORDER_TABS },
    '@/store/remind': { useRemindStore: () => ({ fetchPlayerRemind() {} }) }
  }
  const imports = babelParse(compiled.content, { sourceType: 'module' }).program.body.filter(node => node.type === 'ImportDeclaration')
  let code = compiled.content
  for (const node of [...imports].reverse()) {
    const source = node.source.value
    if (source.endsWith('.vue')) modules[source] = { default: {} }
    assert.ok(modules[source], `missing adapter for ${source}`)
    const bindings = node.specifiers.map(specifier => {
      const name = specifier.type === 'ImportDefaultSpecifier' ? 'default' : specifier.imported.name
      return `const ${specifier.local.name} = __imports[${JSON.stringify(source)}][${JSON.stringify(name)}];`
    }).join('\n')
    code = code.slice(0, node.start) + bindings + code.slice(node.end)
  }
  const component = new Function('__imports', 'uni', `${code}\nreturn __page`)(modules, {
    hideHomeButton() {}, stopPullDownRefresh() { stopped++ }
  })
  const state = component.setup({}, { expose() {} })
  return { state, stopped: () => stopped, async fire(name) {
    await Promise.all(hooks[name].map(fn => fn()))
    await setImmediate()
  } }
}

test('order page snapshots status, ignores stale responses and skips the selected tab', async () => {
  const calls = []
  const page = pageHarness('order', { player: { getMyWork(params) {
    const task = deferred()
    calls.push({ ...task, params })
    return task.promise
  } } })
  await page.fire('onShow')
  await page.fire('onShow')
  assert.equal(calls.length, 1)
  assert.deepEqual(calls[0].params, { pageNum: 1, pageSize: 10, status: '' })
  const switched = page.state.switchTab('ACCEPTED')
  await setImmediate()
  assert.equal(calls[1].params.status, 'ACCEPTED')
  calls[1].resolve(response([9]))
  await switched
  calls[0].resolve(response([1]))
  await setImmediate()
  assert.deepEqual(page.state.orders.value.map(order => order.id), [9])
  await page.state.switchTab('ACCEPTED')
  assert.equal(calls.length, 2)
})

test('order detail return keeps both loaded pages while revalidating them', async () => {
  const calls = []
  const firstRefresh = deferred()
  const page = pageHarness('order', { player: { async getMyWork(params) {
    calls.push(params)
    if (calls.length === 3) return firstRefresh.promise
    return response(Array.from({ length: 10 }, (_, index) => (params.pageNum - 1) * 10 + index + 1), 30)
  } } })
  await page.fire('onShow')
  await page.state.loadMore()
  const previous = page.state.orders.value
  await page.fire('onHide')
  await page.fire('onShow')
  assert.equal(page.state.orders.value, previous)
  assert.equal(previous.length, 20)
  assert.equal(page.state.loading.value, true)
  firstRefresh.resolve(response(Array.from({ length: 10 }, (_, index) => index + 1), 30))
  await setImmediate()
  assert.deepEqual(calls.map(call => call.pageNum), [1, 2, 1, 2])
  assert.equal(page.state.orders.value.length, 20)
  assert.equal(page.state.loading.value, false)
})

test('hall loads categories once and preserves all descendant category ids', async () => {
  let categoryCalls = 0
  const calls = []
  const page = pageHarness('hall', {
    category: { async getAllCategories() {
      categoryCalls++
      return { data: [{ id: 1, name: '主类', parentId: 0 }, { id: 2, parentId: 1 }, { id: 3, parentId: 2 }] }
    } },
    player: { async getOrderHall(params) { calls.push(params); return response() } }
  })
  await page.fire('onShow')
  await page.state.filterCategory(1)
  assert.deepEqual(calls[1], { pageNum: 1, pageSize: 10, categoryIds: '1,2,3' })
  assert.deepEqual(page.state.categories.value.map(category => category.id), [1])
  await page.state.filterCategory(1)
  assert.equal(calls.length, 2)
  await page.fire('onHide')
  await page.fire('onShow')
  assert.equal(categoryCalls, 1)
})

test('hall category failures are retryable and a successful empty category list is cached', async () => {
  let categoryCalls = 0, hallCalls = 0
  const page = pageHarness('hall', {
    category: { async getAllCategories() {
      if (++categoryCalls === 1) throw new Error('分类加载失败')
      return { data: [] }
    } },
    player: { async getOrderHall() { hallCalls++; return response() } }
  })
  await page.fire('onShow')
  assert.equal(page.state.loading.value, false)
  assert.equal(page.state.error.value, '分类加载失败')
  assert.equal(hallCalls, 0)
  await page.state.retry()
  assert.equal(page.state.error.value, '')
  assert.equal(categoryCalls, 2)
  assert.equal(hallCalls, 1)
  await page.fire('onHide')
  await page.fire('onShow')
  assert.equal(categoryCalls, 2, 'empty metadata must not be fetched on every return')
  assert.equal(hallCalls, 1, 'a quick return keeps the loaded hall instead of refetching')
  await page.state.refresh({ force: true })
  assert.equal(hallCalls, 2, 'forced refresh still reaches the hall')
})

test('hall shares pending metadata and never chains a hall request after unload', async () => {
  const metadata = deferred()
  let categoryCalls = 0, hallCalls = 0
  const page = pageHarness('hall', {
    category: { getAllCategories() { categoryCalls++; return metadata.promise } },
    player: { async getOrderHall() { hallCalls++; return response() } }
  })
  await page.fire('onShow')
  await page.fire('onShow')
  await page.fire('onHide')
  await page.fire('onShow')
  assert.equal(categoryCalls, 1)
  await page.fire('onUnload')
  metadata.resolve({ data: [{ id: 1, parentId: 0 }] })
  await setImmediate()
  assert.equal(hallCalls, 0)
  assert.equal(page.state.loading.value, false)
  assert.deepEqual(page.state.categories.value, [])
})

test('hall acceptance refresh waits out a pre-acceptance read before issuing a fresh GET', async () => {
  const calls = []
  const page = pageHarness('hall', { player: { getOrderHall() {
    const task = deferred()
    calls.push(task)
    return task.promise
  } } })
  await page.fire('onShow')
  const accepted = page.state.refreshAfterAccept()
  await setImmediate()
  assert.equal(calls.length, 1)
  calls[0].resolve(response([1, 2]))
  await setImmediate()
  assert.equal(calls.length, 2)
  assert.deepEqual(page.state.orders.value, [])
  calls[1].resolve(response([2]))
  await accepted
  assert.deepEqual(page.state.orders.value.map(order => order.id), [2])
})

for (const kind of ['hall', 'order']) {
  test(`${kind} pull-down refresh always stops its indicator after an API failure`, async () => {
    const fail = async () => { throw new Error('断网') }
    const page = pageHarness(kind, { player: { getOrderHall: fail, getMyWork: fail } })
    await page.fire('onPullDownRefresh')
    assert.equal(page.stopped(), 1)
    assert.equal(page.state.loading.value, false)
    assert.equal(page.state.error.value, '断网')
  })
}
