import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const repoRoot = path.resolve(fileURLToPath(new URL('../../', import.meta.url)))
export const inventoryRelativePath = 'docs/theme-route-inventory-2026-11-13.json'
export const profiles = [
  { id: 'customer-mobile', app: 'customer', viewport: { width: 390, height: 844 }, mobile: true },
  { id: 'customer-small', app: 'customer', viewport: { width: 320, height: 568 }, mobile: true },
  { id: 'admin-pc-wide', app: 'admin', variant: 'pc', viewport: { width: 1440, height: 900 } },
  { id: 'admin-pc-narrow', app: 'admin', variant: 'pc', viewport: { width: 390, height: 844 } },
  { id: 'admin-h5-mobile', app: 'admin', variant: 'h5', viewport: { width: 390, height: 844 }, mobile: true },
  { id: 'admin-h5-wide', app: 'admin', variant: 'h5', viewport: { width: 1440, height: 900 } },
]

export function dDrivePath(value) {
  const absolute = path.resolve(value)
  if (!/^D:[\\/]/i.test(absolute)) throw new Error(`Output must be an absolute D: path: ${absolute}`)
  return absolute
}
export function localOrigin(value) {
  const url = new URL(value)
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('Only a plain loopback HTTP origin is allowed')
  }
  return url.origin
}
export function gitSnapshot() {
  const run = args => execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' }).trim()
  // Paths/status only; never read .env, credentials, user profiles, or untracked file contents.
  return { branch: run(['branch', '--show-current']), head: run(['rev-parse', 'HEAD']), status: run(['status', '--short']).split(/\r?\n/).filter(Boolean) }
}
export async function hashSources() {
  const files = ['delta-mp/pages.json', 'delta-admin-ui/src/router/index.js', 'delta-admin-ui/src/utils/h5-route.js',
    'docs/VIBE_CODING.md', 'docs/development-conventions.md', 'docs/客户实例与授权中心关系.md', 'docs/项目结构说明.md', 'docs/AI协作自检与防回归清单.md']
  return Object.fromEntries(await Promise.all(files.map(async file => [file, createHash('sha256').update(await readFile(path.join(repoRoot, file))).digest('hex')])))
}

// Interpret only static route declarations. No eval, router import, component execution, or guard replacement.
function literal(node) {
  if (!node) return undefined
  if (['StringLiteral', 'NumericLiteral', 'BooleanLiteral'].includes(node.type)) return node.value
  if (node.type === 'NullLiteral') return null
  if (node.type === 'ArrayExpression') return node.elements.map(literal)
  if (node.type === 'ObjectExpression') return Object.fromEntries(node.properties.map(prop => {
    if (prop.type !== 'ObjectProperty' || prop.computed) throw new Error('Unsupported dynamic route property')
    return [prop.key.name ?? prop.key.value, literal(prop.value)]
  }))
  if (node.type === 'Identifier' && ['Layout', 'H5Layout'].includes(node.name)) return `@/layout/${node.name === 'Layout' ? 'index' : 'H5Layout'}.vue`
  if (node.type === 'ArrowFunctionExpression' && node.body.type === 'CallExpression' && node.body.callee.type === 'Import') return literal(node.body.arguments[0])
  throw new Error(`Unsupported route expression: ${node.type}; update inventory parser explicitly`)
}
function walk(node, visit) {
  if (!node || typeof node !== 'object') return
  if (node.type) visit(node)
  for (const value of Object.values(node)) {
    if (Array.isArray(value)) value.forEach(item => walk(item, visit))
    else if (value && typeof value === 'object') walk(value, visit)
  }
}
const joinRoute = (parent, child) => child.startsWith('/') ? child : `${parent.replace(/\/$/, '')}/${child}`.replace(/\/$/, '') || '/'
function flatten(routes, variant, parent = '', inherited = {}, ancestors = []) {
  return routes.flatMap(route => {
    const routePath = joinRoute(parent, route.path)
    const meta = { ...inherited, ...route.meta }
    const roleRequirements = [...ancestors, ...(route.meta?.roles ? [route.meta.roles] : [])]
    if (route.children?.length) return flatten(route.children, variant, routePath, meta, roleRequirements)
    if (!route.component || route.redirect) throw new Error(`Unrenderable leaf requires explicit treatment: ${routePath}`)
    const catchAll = routePath.includes(':pathMatch')
    return [{ id: `${variant}:${routePath}`, app: 'admin', variant, path: routePath,
      samplePath: catchAll ? routePath.replace(':pathMatch(.*)*', '__qa_missing_route__') : routePath.replace(/:id\b/g, '900001'),
      title: meta.title || route.name || routePath, component: route.component, name: route.name || null, meta, roleRequirements,
      fixtureAuth: routePath.endsWith('/login') ? 'anonymous' : 'admin',
      purpose: catchAll ? 'intentional-404' : routePath.endsWith('/403') ? 'intentional-403' : 'function-page' }]
  })
}
const customerQuery = {
  'pages/players/detail': { id: '900001' },
  'pages/product/detail': { id: '900001' },
  'pages/order/create': { productId: '900001', productName: '合成测试服务' },
  'pages/order/detail': { id: '900001' },
  'pages/order/pay': { orderId: '900002', amount: '88.00' },
  'pages/review/create': { orderId: '900001' },
  'pages/complaint/create': { orderId: '900001' },
  'pages/complaint/detail': { id: '900001' },
  'pages/chat/room': { sessionId: '900001', name: '合成测试会话' },
  'pages-player/order/detail': { id: '900001' },
  'pages-player/order/progress': { orderId: '900001' },
  'pages-player/invite/teammate': { orderId: '900001' },
  'pages-player/invite/list': { orderId: '900001' },
  'pages-player/chat/room': { sessionId: '900001', name: '合成测试会话' },
  'pages-player/withdraw/detail': { id: '900001' },
  'pages-player/account/edit': { id: '900001' },
}
export async function buildInventory() {
  const require = createRequire(path.join(repoRoot, 'delta-admin-ui/package.json'))
  const { parse } = require('@babel/parser')
  const pages = JSON.parse(await readFile(path.join(repoRoot, 'delta-mp/pages.json'), 'utf8'))
  const registered = [...pages.pages.map(page => ({ ...page, group: 'pages' })),
    ...(pages.subPackages || pages.subpackages || []).flatMap(group => group.pages.map(page => ({ ...page, path: `${group.root}/${page.path}`, group: group.root })))]
  const customer = registered.map(page => {
    const query = customerQuery[page.path] || {}
    return { id: `customer:${page.path}`, app: 'customer', path: `/${page.path}`, group: page.group, title: page.style?.navigationBarTitleText || page.path,
      samplePath: `/#/${page.path}${Object.keys(query).length ? '?' + new URLSearchParams(query) : ''}`, query,
      fixtureAuth: page.path === 'pages-player/apply/index' ? 'user' : page.path.startsWith('pages-player/') ? 'player' : /^(pages\/login\/|pages-cs\/)/.test(page.path) ? 'anonymous' : 'user',
      purpose: page.group === 'pages-cs' ? 'staff-transfer-unconfigured-fixture' : 'function-page',
      component: `delta-mp/${page.path}.vue` }
  })
  const routerSource = await readFile(path.join(repoRoot, 'delta-admin-ui/src/router/index.js'), 'utf8')
  const ast = parse(routerSource, { sourceType: 'module' })
  let desktopNode
  const extraH5 = []
  walk(ast, node => {
    if (node.type === 'VariableDeclarator' && node.id.name === 'desktopRoutes') desktopNode = node.init
    if (node.type === 'ObjectExpression') {
      const key = name => node.properties.find(p => (p.key?.name || p.key?.value) === name)?.value?.value
      if (key('path') === '/h5/login' || ['H5Forbidden', 'H5NotFound'].includes(key('name'))) extraH5.push(literal(node))
    }
  })
  if (!desktopNode || extraH5.length !== 3) throw new Error('Router structure changed: inspect explicit H5 declarations')
  const desktop = literal(desktopNode)
  const h5Module = await import(pathToFileURL(path.join(repoRoot, 'delta-admin-ui/src/utils/h5-route.js')).href)
  const h5 = h5Module.buildH5Routes(desktop, '@/layout/H5Layout.vue')
  const root = h5.find(route => route.path === '/h5')
  if (!root) throw new Error('Missing actual H5 root')
  root.children.push(...extraH5.filter(route => route.path !== '/h5/login'))
  h5.push(...extraH5.filter(route => route.path === '/h5/login'))
  const admin = [...flatten(desktop, 'pc'), ...flatten(h5, 'h5')]
  if (new Set(customer.map(route => route.path)).size !== customer.length || new Set(admin.map(route => route.id)).size !== admin.length) throw new Error('Duplicate registered routes')
  return { schemaVersion: 1, generatedAt: new Date().toISOString(), classification: 'SYNTHETIC ISOLATED LOCAL VISUAL AUDIT — NOT BUSINESS ACCEPTANCE',
    worktree: repoRoot, git: gitSnapshot(), sourceHashes: await hashSources(),
    derivation: { customer: 'pages.json main pages plus every subpackage; includes staff transfer entry', admin: 'Static AST of desktopRoutes; actual buildH5Routes helper; explicit H5 login/403/404 declarations; inherited metadata and role requirements retained' },
    counts: { customerRegistered: customer.length, customerMain: customer.filter(r => r.group === 'pages').length, player: customer.filter(r => r.group === 'pages-player').length, staffTransfer: customer.filter(r => r.group === 'pages-cs').length,
      adminPcLeaves: admin.filter(r => r.variant === 'pc').length, adminH5Leaves: admin.filter(r => r.variant === 'h5').length,
      adminPcFunctionPages: admin.filter(r => r.variant === 'pc' && r.purpose === 'function-page').length },
    themes: ['default', 'neumorphism'], profiles, routes: { customer, admin },
    limitations: ['Inventory presence never constitutes rendering or business acceptance.', 'Fixtures use generated identities and records; list-empty states and seeded details are explicitly recorded per request.', 'No real login, payment, SMS, upload, settlement, authorization, upgrade, or production operation is exercised.', 'Closed dialogs, every status/data combination, actual iOS/Android/WeChat/QQ, mini-program builds, and subjective visual acceptance need separate validation.', 'Staff transfer fixture leaves staff_web_url empty; staff business pages are audited through the separate admin router.'] }
}
export async function writeInventory() {
  const inventory = await buildInventory()
  const output = dDrivePath(path.join(repoRoot, inventoryRelativePath))
  await mkdir(path.dirname(output), { recursive: true })
  await writeFile(output, JSON.stringify(inventory, null, 2) + '\n')
  return inventory
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const inventory = await writeInventory()
  console.log(JSON.stringify({ output: path.join(repoRoot, inventoryRelativePath), counts: inventory.counts }, null, 2))
}
