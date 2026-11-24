import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed } from 'vue'
import { babelParse, compileScript, compileTemplate, parse } from '@vue/compiler-sfc'

function playerCardHarness(site) {
  const filename = 'components/PlayerListCard.vue'
  const { descriptor, errors } = parse(readFileSync(new URL(`../${filename}`, import.meta.url), 'utf8'), { filename })
  assert.deepEqual(errors, [])
  const compiled = compileScript(descriptor, { id: 'player-card', genDefaultAs: '__card' })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: 'player-card',
    compilerOptions: { bindingMetadata: compiled.bindings }
  })
  assert.deepEqual(template.errors, [])
  const contactCalls = []
  const modules = {
    vue: { computed, ref },
    '@/components/AppImage.vue': { default: {} },
    '@/store/site': { useSiteStore: () => site },
    '@/utils/customer-service': { openCustomerService: async (...args) => contactCalls.push(args) },
    '@/utils/shared-transition.mjs': { navigateWithSharedMedia: ({ navigate }) => navigate() }
  }
  const imports = babelParse(compiled.content, { sourceType: 'module' }).program.body.filter(node => node.type === 'ImportDeclaration')
  let code = compiled.content
  for (const node of [...imports].reverse()) {
    const source = node.source.value
    assert.ok(modules[source], `missing adapter for ${source}`)
    const bindings = node.specifiers.map(specifier => {
      const name = specifier.type === 'ImportDefaultSpecifier' ? 'default' : specifier.imported.name
      return `const ${specifier.local.name} = __imports[${JSON.stringify(source)}][${JSON.stringify(name)}];`
    }).join('\n')
    code = code.slice(0, node.start) + bindings + code.slice(node.end)
  }
  const toasts = [], navigations = []
  const component = new Function('__imports', 'uni', `${code}\nreturn __card`)(modules, {
    showToast(options) { toasts.push(options.title) },
    navigateTo(options) { navigations.push(options.url) }
  })
  const state = component.setup({ player: { id: 5, isOnline: 1 }, compact: false }, { expose() {} })
  return { state, contactCalls, toasts, navigations }
}

test('player card uses the same purchase mode as product detail', async () => {
  const site = {
    orderPurchaseEnabled: ref(true),
    orderContactPurchaseEnabled: ref(false)
  }
  const page = playerCardHarness(site)
  page.state.placeOrder()
  assert.deepEqual(page.navigations, ['/pages/product/list?playerId=5'])
  assert.equal(page.contactCalls.length, 0)

  site.orderPurchaseEnabled.value = false
  site.orderContactPurchaseEnabled.value = true
  await page.state.contactForPurchase()
  assert.equal(page.contactCalls.length, 1)
  assert.deepEqual(page.navigations, ['/pages/product/list?playerId=5'])
})

test('paused player card keeps the contact action available without an online gate', async () => {
  const site = {
    orderPurchaseEnabled: ref(false),
    orderContactPurchaseEnabled: ref(true)
  }
  const page = playerCardHarness(site)
  await page.state.contactForPurchase()
  assert.equal(page.contactCalls.length, 1)
  assert.deepEqual(page.toasts, [])
})
