import {spawnSync} from 'node:child_process'
import {join} from 'node:path'

const mode = process.argv[2]
if (!['dev', 'build'].includes(mode) || !process.argv[3]) {
  console.error('Usage: node scripts/run-uni.mjs <dev|build> <h5|mp-weixin>')
  process.exit(2)
}

const platform = process.argv[3]
const args = []
if (mode === 'build') args.push('build')
args.push('-p', platform)
const command = process.execPath
const cli = join(process.cwd(), 'node_modules', '@dcloudio', 'vite-plugin-uni', 'bin', 'uni.js')
const result = spawnSync(command, [cli, ...args], {
  stdio: 'inherit',
  env: {...process.env, UNI_INPUT_DIR: process.cwd()}
})
if (result.error) console.error(result.error.message)
process.exit(result.status ?? 1)
