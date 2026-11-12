import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  ArrowRight, Avatar, Box, ChatDotRound, CircleCheck, Clock, DataAnalysis, Goods, Link,
  List, Lock, Medal, Menu, Money, Odometer, Picture, Search, Setting, Switch, Tickets,
  User, Wallet, Warning
} from '@element-plus/icons-vue'
import router from './router'
import App from './App.vue'
import './style.css'
import './styles/responsive.css'
import './styles/neumorphism.css'
import { installViewport } from './utils/responsive'
import { installClientPlatform } from './utils/client-platform'

installClientPlatform()
const stopViewport = installViewport()
if (import.meta.hot) import.meta.hot.dispose(stopViewport)

const app = createApp(App)

// Only string-based navigation/input icons need global registration.
for (const [key, component] of Object.entries({
  ArrowRight, Avatar, Box, ChatDotRound, CircleCheck, Clock, DataAnalysis, Goods, Link,
  List, Lock, Medal, Menu, Money, Odometer, Picture, Search, Setting, Switch, Tickets,
  User, Wallet, Warning
})) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
