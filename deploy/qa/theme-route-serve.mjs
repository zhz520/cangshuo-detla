// Local Vite only, no backend proxy, no repository config/cache mutation.
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { repoRoot, dDrivePath } from './theme-route-inventory.mjs'

const app = process.argv[process.argv.indexOf('--app') + 1]
if (!['admin', 'customer'].includes(app)) throw new Error('Usage: node deploy/qa/theme-route-serve.mjs --app <admin|customer> [--port 5317]')
const portIndex = process.argv.indexOf('--port')
const port = portIndex < 0 ? app === 'admin' ? 5317 : 5318 : Number(process.argv[portIndex + 1])
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid local port')
const temp = dDrivePath(process.env.QA_TEMP || 'D:/CodexData/temp/delta-theme-route-audit')
for (const key of ['TEMP', 'TMP', 'TMPDIR']) process.env[key] = temp
process.env.NODE_COMPILE_CACHE = path.join(temp, 'node-compile-cache')
process.env.VITE_API_BASE_URL = '/__qa_api__'
process.env.UNI_OUTPUT_DIR = path.join(temp, 'uni-output')
process.env.UNI_APP_X_CACHE_DIR = path.join(temp, 'uni-cache')
await mkdir(path.join(temp, 'empty-env'), { recursive: true })
const project = path.join(repoRoot, app === 'admin' ? 'delta-admin-ui' : 'delta-mp')
process.env.UNI_INPUT_DIR = project
process.chdir(project)
const fromProject = relative => import(pathToFileURL(path.join(project, relative)).href)
const { createServer } = await fromProject('node_modules/vite/dist/node/index.js')
let config
if (app === 'customer') {
  const cli = await fromProject('node_modules/@dcloudio/vite-plugin-uni/dist/cli/utils.js')
  cli.initEnv('dev', { platform: 'h5', mode: 'development' })
  const uniModule = await fromProject('node_modules/@dcloudio/vite-plugin-uni/dist/index.js')
  const uni = uniModule.default.default || uniModule.default
  config = { plugins: [uni()], build: { target: 'es2018' } }
} else {
  const vue = (await fromProject('node_modules/@vitejs/plugin-vue/dist/index.mjs')).default
  config = { plugins: [vue()], resolve: { alias: { '@': path.join(project, 'src') } } }
}
const server = await createServer({ ...config, root: project, configFile: false, envDir: path.join(temp, 'empty-env'),
  cacheDir: path.join(temp, `${app}-vite-cache`), clearScreen: false,
  build: { ...config.build, outDir: path.join(temp, `${app}-build`) },
  server: { ...config.server, host: '127.0.0.1', port, strictPort: true, open: false, hmr: false, proxy: {},
    fs: { strict: true, allow: [repoRoot, temp] } },
  plugins: [...(config.plugins || []), { name: 'qa-no-backend', configureServer(vite) {
    vite.middlewares.use((req, res, next) => {
      const pathname = new URL(req.url, 'http://127.0.0.1').pathname
      if (pathname.startsWith('/__qa_api__/') || (pathname.startsWith('/api/') && !(app === 'customer' && /^\/api\/[a-z-]+\.js$/.test(pathname)))) {
        res.writeHead(503, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ code: 503, msg: 'QA_BROWSER_FIXTURE_REQUIRED', data: null }))
      } else next()
    })
  } }],
})
await server.listen()
console.log(`SYNTHETIC QA ${app}: http://127.0.0.1:${port}; no backend proxy; cache/output ${temp}`)
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await server.close(); process.exit(0) })
