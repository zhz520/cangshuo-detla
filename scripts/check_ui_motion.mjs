// npm ci in delta-admin-ui supplies the same parser used by the theme/permission audit.
// --write updates generated artifacts for review; default mode is read-only (CI).
import assert from 'node:assert/strict'
import { readFile, writeFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import { buildInventory, repoRoot } from '../deploy/qa/theme-route-inventory.mjs'
import { legacyConfigTabs } from '../delta-admin-ui/src/utils/config-sections.js'

const write = process.argv.includes('--write')
const file = relative => path.join(repoRoot, relative)
const get = async relative => (await readFile(file(relative), 'utf8')).replaceAll('\r\n', '\n')
const json = x => JSON.stringify(x, null, 2) + '\n'
async function output(relative, content) {
  if (write) await writeFile(file(relative), content)
  else assert.equal(await get(relative), content, relative + ' is stale: run node scripts/check_ui_motion.mjs --write and review the diff')
}
const spec = JSON.parse(await get('contracts/ui-motion.json'))
assert.equal(spec.schemaVersion, 1)
for (const key of ['enter', 'feedback', 'pulse']) assert.match(spec.tokens[key], /^[1-9]\d*ms$/, 'invalid motion duration: ' + key)
assert.match(spec.tokens.distance, /^\d+(\.\d+)?px$/)
assert.match(spec.tokens.ease, /^cubic-bezier\([\d., -]+\)$/)
const vars = Object.entries(spec.tokens).map(([k,v]) => '  --delta-motion-' + k + ': ' + v + ';').join('\n')
await output('delta-admin-ui/src/styles/motion-tokens.css', '/* Generated from contracts/ui-motion.json */\n:root {\n' + vars + '\n}\n')
await output('delta-mp/styles/motion-tokens.scss', '/* Generated from contracts/ui-motion.json */\npage, :root {\n' + vars + '\n}\n')

const {routes, themes, redirects} = await buildInventory()
const rows = [...routes.admin,...routes.customer].map(r => {
  const group = r.app === 'admin' ? 'admin-' + r.variant : r.group
  const alias = r.app === 'admin' && legacyConfigTabs[r.path.replace(/^\/h5(?=\/)/, '')]
  const login = r.app === 'admin' && r.path.endsWith('/login')
  const purpose = alias ? 'redirect:system/config?tab=' + alias : login ? 'standalone-login' : r.purpose
  const host = alias ? 'destination' : login ? 'app' : r.app === 'admin' ? 'layout' : 'uni-page'
  const roles = r.roleRequirements?.map(x => x.join('|')).join('&') || r.fixtureAuth
  return [group, r.path, r.component, purpose, roles, themes.join('|'), host, r.id].join('\t')
}).concat(redirects.map(r => ['admin-' + r.variant, r.path, '-', 'redirect:' + (typeof r.target === 'string' ? r.target : JSON.stringify(r.target)), 'destination', themes.join('|'), 'destination', r.variant + ':redirect:' + r.path].join('\t'))).sort()
assert.equal(new Set(rows).size, rows.length)
await output('contracts/ui-motion-routes.tsv', ['surface\troute\tview\tpurpose\troleOrFixture\tthemes\thost\tcaseKey',...rows,''].join('\n'))

// Per-file ceilings catch new custom page spinners without treating business animation
// (e.g. a chart or unread badge) as route motion. Lower the ceiling as pages migrate.
const patterns = {
  blocking: /\buni\.showLoading\s*\(/g,
  localMotion: /@(?:-webkit-)?keyframes\s+[^\s{]*(?:load|spin|skeleton|shimmer|page-reveal|page-enter|route-enter)[^\s{]*/gi
}
const observed = {}
const registered = new Set(routes.customer.map(r => r.component))
const unregistered = []
async function scan(dir) {
  for (const ent of await readdir(file(dir), {withFileTypes:true})) {
    const key = dir + '/' + ent.name
    if (ent.isDirectory()) { await scan(key); continue }
    if (!ent.isFile() || !key.endsWith('.vue')) continue
    const src = await get(key)
    if (key.startsWith('delta-mp/') && !registered.has(key)) unregistered.push(key)
    for (const [kind,re] of Object.entries(patterns)) {
      const count = [...src.matchAll(re)].length
      if (count) (observed[key] ??= {})[kind] = count
    }
  }
}
for (const dir of ['delta-admin-ui/src/views','delta-mp/pages','delta-mp/pages-player','delta-mp/pages-cs']) await scan(dir)
// A file outside pages.json can still be an imported component; never delete from this list alone.
await output('contracts/ui-motion-unregistered.txt', ['# Not registered as uni-app routes. Verify imports and external links before removal.', ...unregistered.sort(), ''].join('\n'))
const baselineFile = 'contracts/ui-motion-exceptions.json'
if (process.argv.includes('--review-exceptions') && write) {
  await output(baselineFile, json(Object.fromEntries(Object.entries(observed).sort(([a],[b])=>a<b?-1:a>b?1:0))))
}
const known = JSON.parse(await get(baselineFile))
for (const p of Object.keys(known)) assert.ok(spec.exceptions?.[p]?.trim(), p + ': missing exception rationale/removal criteria')
if (known['delta-mp/pages-cs/dashboard/index.vue']) assert.ok(!registered.has('delta-mp/pages-cs/dashboard/index.vue'), 'remove legacy loading before registering the staff dashboard')
for (const [p,counts] of Object.entries(observed)) for (const [k,n] of Object.entries(counts))
  assert.ok(n <= (known[p]?.[k] ?? 0), p + ': new ' + k + ' implementation; use the shared adapter or justify the exception in review')
// A correct generated file that is not imported does not satisfy the contract.
for (const [p, tokens] of [
  ['delta-admin-ui/src/main.js', ['motion-tokens.css']],
  ['delta-admin-ui/src/App.vue', ['PageViewport']],
  ['delta-admin-ui/src/layout/index.vue', ['PageViewport']],
  ['delta-admin-ui/src/layout/H5Layout.vue', ['PageViewport']],
  ['delta-mp/App.vue', ['motion-tokens.scss', 'installPageMotion']],
  ['delta-mp/components/PageState.vue', ['LoadFeedback']],
]) {
  const src = await get(p)
  for (const token of tokens) assert.ok(src.includes(token), p + ': shared adapter/token missing: ' + token)
}
console.log('Motion contract OK: %s admin routes, %s uni-app registrations. Static coverage only.',routes.admin.length,routes.customer.length)
