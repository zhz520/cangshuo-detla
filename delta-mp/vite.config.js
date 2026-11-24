import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import { localHeaderShadows } from './scripts/uni-header-shadows.mjs'
import {readFileSync} from 'node:fs'

const packageVersion = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version
// Keep every emitted chunk ASCII-only and version-scoped. A non-ASCII chunk name
// can survive the local build but be mangled by a Linux unzip or proxy cache.
const h5Assets = 'assets/v' + packageVersion

export default defineConfig({
  ...(process.env.UNI_PLATFORM === 'h5' ? { css: { postcss: { plugins: [localHeaderShadows()] } } } : {}),
  plugins: [uni(),{name:'instance-runtime-placeholder',apply:'build',generateBundle(){
    if(process.env.UNI_PLATFORM==='h5'){
      this.emitFile({type:'asset',fileName:'instance-config.js',source:readFileSync(new URL('./public/instance-config.js',import.meta.url),'utf8')})
      this.emitFile({type:'asset',fileName:'assets/boot-tabbar.js',source:readFileSync(new URL('./public/boot-tabbar.js',import.meta.url),'utf8')})
    }
  }}],
  server: {
    host: '127.0.0.1',
    strictPort: true,
    fs: { strict: true }
  },
  build: {
    sourcemap: false,
    target: 'es2018',
    cssTarget: ['chrome79', 'safari13'],
    // Imported SVGs are small data modules. Inline them instead of emitting
    // Chinese-named JS assets that some Linux unzip/proxy combinations mangle.
    assetsInlineLimit: file => /[.]svg$/i.test(file) ? 1024 * 1024 : 4096,
    ...(process.env.UNI_PLATFORM === 'h5' ? {
      rollupOptions: { output: {
        entryFileNames: h5Assets + '/entry-[hash].js',
        chunkFileNames: h5Assets + '/chunk-[hash].js',
        assetFileNames: h5Assets + '/asset-[hash][extname]'
      } }
    } : {})
  }
})
