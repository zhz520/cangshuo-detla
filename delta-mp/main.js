import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
// #ifdef H5
import { mountAgeGate } from './utils/age-gate.js'
import './styles/age-gate.css'
import './styles/auth-flow.h5.css'
// #endif

export function createApp() {
  const app = createSSRApp(App)
  const pinia = createPinia()
  app.use(pinia)
  // #ifdef H5
  mountAgeGate()
  // #endif
  return { app }
}
