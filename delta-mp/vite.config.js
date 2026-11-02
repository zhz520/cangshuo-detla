import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import {readFileSync} from 'node:fs'

export default defineConfig({
  plugins: [uni(),{name:'instance-runtime-placeholder',apply:'build',generateBundle(){
    if(process.env.UNI_PLATFORM==='h5')this.emitFile({type:'asset',fileName:'instance-config.js',source:readFileSync(new URL('./public/instance-config.js',import.meta.url),'utf8')})
  }}],
  server: {
    host: '127.0.0.1',
    strictPort: true,
    fs: { strict: true }
  },
  build: {
    sourcemap: false,
    target: 'es2018',
    cssTarget: ['chrome79', 'safari13']
  }
})
