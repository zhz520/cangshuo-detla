import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { Avatar, Box, ChatDotRound, DataAnalysis, Goods, List, Lock, Menu, Odometer, Picture, Search, Setting, User } from '@element-plus/icons-vue'
import router from './router'
import App from './App.vue'
import './style.css'
import './styles/responsive.css'
import { installViewport } from './utils/responsive'

const stopViewport = installViewport()
if (import.meta.hot) import.meta.hot.dispose(stopViewport)

const app = createApp(App)

// Only string-based navigation/input icons need global registration.
for (const [key, component] of Object.entries({ Avatar, Box, ChatDotRound, DataAnalysis, Goods, List, Lock, Menu, Odometer, Picture, Search, Setting, User })) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
