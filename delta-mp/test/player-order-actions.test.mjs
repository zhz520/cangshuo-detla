import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setImmediate } from 'node:timers/promises'
import { ref, computed } from 'vue'
import { babelParse, compileScript, compileTemplate, parse } from '@vue/compiler-sfc'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function pageHarness(kind, overrides = {}) {
  const filename = `pages-player/order/${kind}.vue`
  const { descriptor, errors } = parse(readFileSync(new URL(`../${filename}`, import.meta.url), 'utf8'), { filename })
  assert.deepEqual(errors, [])
  const compiled = compileScript(descriptor, { id: kind, genDefaultAs: '__page' })
  assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename, id: kind,
    compilerOptions: { bindingMetadata: compiled.bindings } }).errors, [])
  const hooks = Object.fromEntries(['onLoad', 'onShow', 'onHide', 'onUnload'].map(name => [name, []]))
  const toasts = [], confirmations = [], timers = new Map()
  let navigations = 0, timerId = 0
  const modules = {
    vue: { ref, computed },
    '@dcloudio/uni-app': Object.fromEntries(Object.entries(hooks).map(([name, callbacks]) => [name, fn => callbacks.push(fn)])),
    '@/api/player': {
      getPlayerOrderDetail: async () => ({ data: { id: 7, status: 'IN_PROGRESS' } }),
      getPlayerOrderProgress: async () => ({ data: [] }),
      startOrder: async () => ({}), completeOrder: async () => ({}), rejectAssign: async () => ({}),
      replaceSelf: async () => ({}), replaceAll: async () => ({}), replaceTeammate: async () => ({}),
      submitWorkProgress: async () => ({}), ...overrides.player
    },
    '@/utils/subscribe': { requestPlayerSubscribe: overrides.subscribe || (async () => ({})) }
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
  const uni = {
    showToast(options) { toasts.push(options.title) }, showModal(options) { confirmations.push(options) },
    navigateBack() { navigations++ }, navigateTo() { navigations++ },
    previewImage() {}, setClipboardData() {}
  }
  const element = { classList: { remove() {} } }
  const component = new Function('__imports', 'uni', 'document', 'setTimeout', 'clearTimeout', `${code}\nreturn __page`)(
    modules, uni, { documentElement: element, body: element },
    fn => { const id = ++timerId; timers.set(id, fn); return id }, id => timers.delete(id)
  )
  const state = component.setup({}, { expose() {} })
  return { state, toasts, confirmations, timers, navigations: () => navigations,
    async fire(name, args) { await Promise.all(hooks[name].map(fn => fn(args))); await setImmediate() },
    runTimers() { for (const [id, fn] of timers) { timers.delete(id); fn() } }
  }
}
async function loadedDetail(overrides) {
  const page = pageHarness('detail', overrides)
  await page.fire('onLoad', { id: '7' })
  await page.fire('onShow')
  assert.equal(page.state.loading.value, false)
  return page
}

test('detail initial error is retryable and duplicate show calls issue one request', async () => {
  const first = deferred()
  let reads = 0
  const page = pageHarness('detail', { player: { async getPlayerOrderDetail() {
    if (++reads === 1) return first.promise
    return { data: { id: 7, status: 'ACCEPTED' } }
  } } })
  assert.equal(page.state.loading.value, true)
  await page.fire('onLoad', { id: '7' })
  await page.fire('onShow')
  await page.fire('onShow')
  assert.equal(reads, 1)
  first.reject(new Error('详情断网'))
  await setImmediate()
  assert.equal(page.state.loading.value, false)
  assert.equal(page.state.error.value, '详情断网')
  assert.equal(page.state.order.value, null)
  await page.state.loadDetail()
  assert.equal(reads, 2)
  assert.equal(page.state.error.value, '')
  assert.equal(page.state.order.value.id, 7)
})

test('history failure preserves the order and previous history and retries independently', async () => {
  let detailReads = 0, historyReads = 0
  const page = await loadedDetail({ player: {
    async getPlayerOrderDetail() { detailReads++; return { data: { id: 7, status: 'IN_PROGRESS' } } },
    async getPlayerOrderProgress() {
      if (++historyReads === 2) throw new Error('进度断网')
      return { data: [{ content: `记录${historyReads}` }] }
    }
  } })
  const history = page.state.progressList.value
  await page.state.loadDetail()
  assert.equal(page.state.order.value.id, 7)
  assert.equal(page.state.error.value, '')
  assert.equal(page.state.progressError.value, '进度断网')
  assert.equal(page.state.progressList.value, history)
  await page.state.loadProgress()
  assert.equal(detailReads, 2)
  assert.equal(historyReads, 3)
  assert.equal(page.state.progressError.value, '')
})

test('hide/show isolates stale detail reads and fetches fresh after the pending GET', async () => {
  const calls = []
  const page = pageHarness('detail', { player: { getPlayerOrderDetail() {
    const task = deferred(); calls.push(task); return task.promise
  } } })
  await page.fire('onLoad', { id: '7' })
  await page.fire('onShow')
  await page.fire('onHide')
  await page.fire('onShow')
  assert.equal(calls.length, 1)
  calls[0].resolve({ data: { id: 7, status: 'ACCEPTED' } })
  await setImmediate()
  assert.equal(page.state.order.value, null)
  assert.equal(page.state.loading.value, true)
  calls[1].resolve({ data: { id: 7, status: 'IN_PROGRESS' } })
  await setImmediate()
  assert.equal(page.state.order.value.status, 'IN_PROGRESS')
})

test('start action locks repeated clicks, catches errors and supports explicit retry', async () => {
  const first = deferred()
  let writes = 0
  const page = await loadedDetail({ player: { async startOrder(id) {
    assert.equal(id, '7')
    if (++writes === 1) return first.promise
    return {}
  } } })
  const start = page.state.doStart()
  await page.state.doStart()
  assert.equal(writes, 1)
  assert.equal(page.state.actionBusy.value, 'start')
  first.reject({ msg: '开始失败' })
  await start
  assert.equal(page.state.actionBusy.value, '')
  assert.equal(page.state.actionError.value, '开始失败')
  await page.state.retryAction()
  assert.equal(writes, 2)
  assert.equal(page.state.actionError.value, '')
})

test('successful write plus failed refresh offers a read retry without repeating the write', async () => {
  let reads = 0, writes = 0
  const page = await loadedDetail({ player: {
    async getPlayerOrderDetail() {
      if (++reads === 2) throw new Error('刷新断网')
      return { data: { id: 7, status: 'ACCEPTED' } }
    },
    async startOrder() { writes++ }
  } })
  await page.state.doStart()
  assert.equal(page.state.error.value, '刷新断网')
  assert.equal(page.state.actionError.value, '')
  await page.state.doStart()
  assert.equal(writes, 1)
  await page.state.loadDetail()
  assert.equal(writes, 1)
  assert.equal(page.state.error.value, '')
})

test('completion validates images, locks before subscription, preserves failed input and retries once', async () => {
  const subscription = deferred(), firstWrite = deferred()
  const payloads = []
  let subscriptions = 0
  const page = await loadedDetail({
    subscribe: async () => { if (++subscriptions === 1) return subscription.promise },
    player: { async completeOrder(id, payload) {
      payloads.push({ id, payload })
      if (payloads.length === 1) return firstWrite.promise
    } }
  })
  page.state.doComplete()
  await page.state.submitComplete()
  assert.equal(subscriptions, 0)
  page.state.endServiceImages.value = ['end.png']
  const complete = page.state.submitComplete()
  await page.state.submitComplete()
  page.state.closeCompleteModal()
  assert.equal(page.state.showCompleteModal.value, true)
  assert.equal(subscriptions, 1)
  subscription.resolve()
  await setImmediate()
  assert.deepEqual(payloads, [{ id: '7', payload: { images: 'end.png' } }])
  firstWrite.reject({ msg: '完成失败' })
  await complete
  assert.deepEqual(page.state.endServiceImages.value, ['end.png'])
  assert.equal(page.state.showCompleteModal.value, true)
  assert.equal(page.state.actionError.value, '完成失败')
  await page.state.retryAction()
  assert.equal(payloads.length, 2)
  assert.equal(page.state.showCompleteModal.value, false)
  assert.deepEqual(page.state.endServiceImages.value, [])
})

test('reject confirmation is locked before the modal and cancellation releases the lock', async () => {
  let writes = 0
  const page = await loadedDetail({ player: { async rejectAssign() { writes++ } } })
  const cancelled = page.state.doReject()
  await page.state.doReject()
  assert.equal(page.confirmations.length, 1)
  page.confirmations[0].success({ confirm: false })
  await cancelled
  assert.equal(writes, 0)
  assert.equal(page.state.actionBusy.value, '')
  const confirmed = page.state.doReject()
  page.confirmations[1].success({ confirm: true })
  await confirmed
  await page.state.doReject()
  assert.equal(writes, 1)
  assert.equal(page.navigations(), 1)
})

for (const type of ['all', 'self', 'teammate']) {
  test(`replacement ${type} retains its business endpoint and prevents repeated confirmation`, async () => {
    const writes = []
    const page = await loadedDetail({ player: {
      async replaceAll(id) { writes.push(['all', id]) },
      async replaceSelf(id) { writes.push(['self', id]) },
      async replaceTeammate(id) { writes.push(['teammate', id]) }
    } })
    page.state.openReplaceModal()
    const replace = page.state.doReplace(type)
    await page.state.doReplace(type)
    assert.equal(page.confirmations.length, 1)
    page.state.closeReplaceModal()
    assert.equal(page.state.showReplaceModal.value, true)
    page.confirmations[0].success({ confirm: true })
    await replace
    assert.deepEqual(writes, [[type, '7']])
    assert.equal(page.state.showReplaceModal.value, false)
    if (type === 'teammate') assert.equal(page.timers.size, 0)
    else {
      assert.equal(page.timers.size, 1)
      await page.fire('onUnload')
      page.runTimers()
      assert.equal(page.navigations(), 0, 'leaving the page must cancel delayed navigateBack')
    }
  })
}

test('unload during a write suppresses follow-up toasts and detail requests', async () => {
  const write = deferred()
  let reads = 0
  const page = await loadedDetail({ player: {
    async getPlayerOrderDetail() { reads++; return { data: { id: 7 } } },
    startOrder: () => write.promise
  } })
  const start = page.state.doStart()
  await page.fire('onUnload')
  write.resolve()
  await start
  assert.equal(reads, 1)
  assert.deepEqual(page.toasts, [])
})

test('accept locks its subscription prompt and opens the existing captcha dialog once', async () => {
  const subscription = deferred()
  let prompts = 0, opens = 0
  const page = await loadedDetail({ subscribe: async () => { prompts++; return subscription.promise } })
  page.state.grabDialog.value = { async open(id) { assert.equal(id, '7'); opens++ } }
  const accept = page.state.doAccept()
  await page.state.doAccept()
  assert.equal(prompts, 1)
  subscription.resolve()
  await accept
  assert.equal(opens, 1)
})

test('progress validates route and content before sending a write', async () => {
  let writes = 0
  const page = pageHarness('progress', { player: { async submitWorkProgress() { writes++ } } })
  page.state.content.value = '内容'
  await page.state.submit()
  assert.equal(writes, 0)
  await page.fire('onLoad', { orderId: '7' })
  page.state.content.value = '   '
  await page.state.submit()
  assert.equal(writes, 0)
  assert.deepEqual(page.toasts, ['请描述进度'])
})

test('progress failure retains its form, retry sends once and success stays locked until return', async () => {
  const first = deferred(), calls = []
  const page = pageHarness('progress', { player: { async submitWorkProgress(id, payload) {
    calls.push({ id, payload })
    if (calls.length === 1) return first.promise
  } } })
  await page.fire('onLoad', { orderId: '7' })
  page.state.content.value = '正在服务'
  page.state.images.value = ['a.png', 'b.png']
  const submit = page.state.submit()
  await page.state.submit()
  assert.equal(calls.length, 1)
  assert.equal(page.state.submitting.value, true)
  first.reject({ msg: '提交断网' })
  await submit
  assert.equal(page.state.error.value, '提交断网')
  assert.equal(page.state.submitting.value, false)
  assert.equal(page.state.content.value, '正在服务')
  assert.deepEqual(page.state.images.value, ['a.png', 'b.png'])
  await page.state.submit()
  assert.deepEqual(calls[1], { id: '7', payload: { content: '正在服务', images: 'a.png,b.png' } })
  await page.state.submit()
  assert.equal(calls.length, 2)
  assert.equal(page.state.submitted.value, true)
  page.runTimers()
  assert.equal(page.navigations(), 1)
})

test('progress unload suppresses late completion and cancels scheduled navigation', async () => {
  const pending = deferred()
  const page = pageHarness('progress', { player: { submitWorkProgress: () => pending.promise } })
  await page.fire('onLoad', { orderId: '7' })
  page.state.content.value = '进度'
  const submit = page.state.submit()
  await page.fire('onUnload')
  pending.resolve()
  await submit
  assert.deepEqual(page.toasts, [])
  assert.equal(page.timers.size, 0)
  const saved = pageHarness('progress')
  await saved.fire('onLoad', { orderId: '7' })
  saved.state.content.value = '进度'
  await saved.state.submit()
  await saved.fire('onUnload')
  saved.runTimers()
  assert.equal(saved.navigations(), 0)
})
