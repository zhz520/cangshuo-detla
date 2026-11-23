import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { effectScope, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { viewportKey } from '../src/utils/page-viewport.js'
import { useDelayedBusy } from '../src/composables/useDelayedBusy.js'

const read = p => readFileSync(new URL('../src/' + p, import.meta.url), 'utf8')

test('route history changes exactly one outlet and query/hash changes neither', async () => {
  const paths = ['/login', '/h5/login', '/dashboard', '/player/:id', '/h5/dashboard', '/h5/player/:id']
  const router = createRouter({ history: createMemoryHistory(), routes: paths.map(path => ({ path, component: {} })) })
  const keys = async target => {
    await router.push(target)
    const path = router.currentRoute.value.path
    return [viewportKey(path, true), viewportKey(path)]
  }
  const pc = await keys('/player/1')
  assert.deepEqual(await keys('/player/1?taskId=2#top'), pc)
  assert.deepEqual(await keys('/player/2'), [pc[0], '/player/2'])
  assert.deepEqual(await keys('/dashboard'), [pc[0], '/dashboard'])
  const h5 = await keys('/h5/dashboard')
  assert.notEqual(h5[0], pc[0])
  assert.deepEqual(await keys('/h5/player/1'), [h5[0], '/h5/player/1'])
  const login = await keys('/h5/login')
  assert.deepEqual(await keys('/h5/login?redirect=/h5/player/1'), login)
  assert.notEqual(login[0], h5[0])
  assert.notEqual((await keys('/login'))[0], login[0])
})

test('only the shell appears on mount; demo content is selected before the business page mounts', () => {
  const viewport = read('components/PageViewport.vue')
  assert.match(viewport, /:appear="shell"/)
  assert.match(viewport, /@leave="removeImmediately"/)
  assert.doesNotMatch(viewport, /mode="out-in"|fullPath|KeepAlive/)
  for (const p of ['layout/index.vue', 'layout/H5Layout.vue']) {
    const shell = read(p)
    assert.match(shell, /<PageViewport v-if="Component" :path="pageRoute.path"/)
    assert.match(shell, /demoMode && canonicalAdminPath\(pageRoute.path\) === '\/system\/config'".*read-only/)
    assert.match(shell, /<DemoReadOnlyPage[^>]*v-else-if="demoMode/)
    assert.doesNotMatch(shell, /<transition|:key="[^"]*fullPath/i)
  }
  assert.match(read('style.css'), /prefers-reduced-motion: reduce[\s\S]*?\.viewport-enter-from \{ opacity: 1; transform: none;/)
})

test('fast work never flashes, overlapping work reveals once and disposal cancels a pending cue', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const scope = effectScope(), busy = ref(false)
  const visible = scope.run(() => useDelayedBusy(busy, 100))
  busy.value = true
  t.mock.timers.tick(99)
  assert.equal(visible.value, false)
  busy.value = false
  t.mock.timers.tick(100)
  assert.equal(visible.value, false)
  busy.value = true
  t.mock.timers.tick(100)
  assert.equal(visible.value, true)
  busy.value = false
  assert.equal(visible.value, false)
  busy.value = true
  scope.stop()
  t.mock.timers.tick(100)
  assert.equal(visible.value, false)
})
