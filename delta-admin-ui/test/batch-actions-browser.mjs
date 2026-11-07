import { createServer } from 'node:http'
import { readFile, stat, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { chromium, devices } from 'file:///D:/codeStudy/code/cangshuo-detla-authorize/node_modules/playwright/index.mjs'
import { expect } from 'file:///D:/codeStudy/code/cangshuo-detla-authorize/node_modules/playwright/test.mjs'

const root = path.resolve('dist')
const output = path.resolve(process.env.QA_OUTPUT || 'D:/CodexData/outputs/cangshuo-detla-1.7.0')
await mkdir(output, { recursive: true })
const server = createServer(async (req, res) => {
  try {
    let file = path.resolve(root, '.' + decodeURIComponent(new URL(req.url, 'http://local').pathname))
    try { if (!(await stat(file)).isFile()) file = path.join(root, 'index.html') } catch { file = path.join(root, 'index.html') }
    res.writeHead(200, { 'Content-Type': { '.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png' }[path.extname(file)] || 'application/octet-stream' })
    res.end(await readFile(file))
  } catch { res.writeHead(404); res.end() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${server.address().port}`
const results = []

function pageData(pathname) {
  if (pathname === '/product/list') return { records: [
    { id: 101, categoryId: 7, name: '测试商品A', price: 50, status: 1, salesCount: 3, createdAt: '2026-11-07 10:00:00' },
    { id: 102, categoryId: 8, name: '测试商品B', price: 88, status: 0, salesCount: 1, createdAt: '2026-11-07 11:00:00' }
  ], total: 2 }
  if (pathname === '/product/category/all') return [
    { id: 7, name: '三角洲', status: 1 },
    { id: 8, name: '陪玩', status: 1 },
    { id: 9, name: '已停用分类', status: 0 }
  ]
  if (pathname === '/admin/user/list') return { records: [
    { id: 7, nickname: '测试用户A', phone: '13800000007', avatar: '', status: 1, balance: 20, passwordSet: true, createdAt: '2026-11-07 09:00:00' },
    { id: 8, nickname: '测试用户B', phone: '13800000008', avatar: '', status: 0, balance: 0, passwordSet: false, createdAt: '2026-11-07 09:10:00' }
  ], total: 2 }
  if (pathname === '/admin/player/list') return { records: [
    { id: 1, nickname: '待审核打手', phone: '13900000001', avatar: '', level: 1, status: 'PENDING', balance: 0, completedOrders: 0, isOnline: 0, createdAt: '2026-11-07 08:00:00' },
    { id: 2, nickname: '冻结打手', phone: '13900000002', avatar: '', level: 2, status: 'FROZEN', balance: 10, completedOrders: 2, isOnline: 0, createdAt: '2026-11-07 08:10:00' }
  ], total: 2 }
  if (pathname === '/admin/player-level/list') return [{ id: 1, name: '普通', status: 1 }, { id: 2, name: '高级', status: 1 }, { id: 3, name: '停用等级', status: 0 }]
  if (pathname === '/cs/permissions') return ['*']
  if (pathname === '/system/config/site') return { site_name: '见高山电竞', site_admin_title: '见高山电竞管理后台' }
  return []
}

try {
  for (const item of [{ name: 'Desktop', options: { viewport: { width: 1440, height: 900 } } }, { name: 'Mobile', options: devices['Pixel 7'] }]) {
    const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined })
    const context = await browser.newContext(item.options)
    const page = await context.newPage()
    const errors = [], writes = [], checks = []
    page.on('pageerror', error => errors.push(error.message))
    await context.addInitScript(() => {
      localStorage.setItem('delta_admin_token', 'batch-ui-test')
      localStorage.setItem('delta_admin_user', JSON.stringify({ adminId: 1, nickname: '管理员', role: 'admin' }))
    })
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url())
      const pathname = url.pathname.replace(/^\/api/, '')
      const method = route.request().method()
      let data = pageData(pathname), code = 200
      if (method === 'POST' && pathname.startsWith('/product/batch/')) {
        writes.push({ page: 'product', pathname, body: route.request().postDataJSON() })
        data = { success: route.request().postDataJSON().ids.length, failed: 0, failedIds: [], messages: [] }
      } else if (method === 'POST' && pathname.startsWith('/admin/user/batch/')) {
        writes.push({ page: 'user', pathname, body: route.request().postDataJSON() })
        data = { success: route.request().postDataJSON().ids.length, failed: 0, failedIds: [], messages: [] }
      } else if (method === 'POST' && pathname.startsWith('/admin/player/batch/')) {
        writes.push({ page: 'player', pathname, body: route.request().postDataJSON() })
        data = { success: route.request().postDataJSON().ids.length, failed: 0, failedIds: [], messages: [] }
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code, data, msg: '操作成功' }) })
    })
    async function selectRow(text) {
      await page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: text }).first().locator('.el-checkbox').click()
    }
    async function confirmAction(button, confirm = '确认处理') {
      await page.getByRole('button', { name: button, exact: true }).click()
      await page.locator('.el-message-box').getByRole('button', { name: confirm, exact: true }).click()
      await page.waitForTimeout(120)
    }
    try {
      await page.goto(base + '/product/list', { waitUntil: 'networkidle' })
      await expect(page.getByText('商品管理', { exact: true }).first()).toBeVisible()
      await selectRow('测试商品A')
      await expect(page.getByText('已选择 1 项', { exact: false })).toBeVisible()
      await page.screenshot({ path: path.join(output, `batch-action-bar-${item.name}.png`), fullPage: true, animations: 'disabled' })
      await confirmAction('批量下架')
      const statusWrite = writes.find(write => write.pathname === '/product/batch/status')
      if (!statusWrite || JSON.stringify(statusWrite.body) !== JSON.stringify({ ids: [101], status: 0 })) throw Error('product batch status body mismatch')
      checks.push('商品批量下架')

      await selectRow('测试商品B')
      await page.getByRole('button', { name: '调整分类', exact: true }).click()
      await expect(page.locator('.el-dialog').getByText('已停用分类')).toHaveCount(0)
      await page.locator('.el-dialog').getByRole('button', { name: '取消', exact: true }).click()
      await page.getByRole('button', { name: '调整价格', exact: true }).click()
      await expect(page.locator('.el-dialog').getByText(/在当前价格基础上上调/)).toBeVisible()
      await page.locator('.el-dialog').getByRole('button', { name: '取消', exact: true }).click()
      checks.push('商品批量分类与调价弹窗')

      await page.goto(base + '/user/list', { waitUntil: 'networkidle' })
      await expect(page.getByText('用户管理', { exact: true }).first()).toBeVisible()
      await selectRow('测试用户A')
      await confirmAction('批量封禁')
      const userWrite = writes.find(write => write.pathname === '/admin/user/batch/status')
      if (!userWrite || JSON.stringify(userWrite.body) !== JSON.stringify({ ids: [7], status: 0 })) throw Error('user batch status body mismatch')
      checks.push('用户批量封禁')

      await page.goto(base + '/player', { waitUntil: 'networkidle' })
      await expect(page.getByText('打手管理', { exact: true }).first()).toBeVisible()
      await selectRow('待审核打手')
      await confirmAction('批量通过', '确认通过')
      const playerWrite = writes.find(write => write.pathname === '/admin/player/batch/approve')
      if (!playerWrite || JSON.stringify(playerWrite.body) !== JSON.stringify({ ids: [1] })) throw Error('player batch approve body mismatch')
      checks.push('打手批量审核')

      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2)
      if (overflow) throw Error('horizontal page overflow')
      if (errors.length) throw Error(errors.join('\n'))
      await page.screenshot({ path: path.join(output, `batch-actions-${item.name}.png`), fullPage: true, animations: 'disabled' })
      results.push({ name: item.name, passed: true, checks })
    } finally {
      await context.close()
      await browser.close()
    }
  }
  await writeFile(path.join(output, 'batch-actions-browser.json'), JSON.stringify(results, null, 2))
  console.log(JSON.stringify(results))
} finally {
  await new Promise(resolve => server.close(resolve))
}
