<template>
  <div class="error-page">
    <h1>403</h1>
    <p>抱歉，您没有权限访问该页面</p>
    <el-button type="primary" @click="goHome">{{ h5 ? '返回工作台' : '返回首页' }}</el-button>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isH5Path } from '@/utils/h5-route'

const route = useRoute()
const router = useRouter()
const h5 = computed(() => isH5Path(route.path))
function goHome() { router.push(h5.value ? '/h5/dashboard' : '/dashboard') }
</script>
<style scoped>
.error-page { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: var(--app-viewport-height); padding: calc(16px + var(--app-safe-top)) calc(16px + var(--app-safe-right)) calc(16px + var(--app-safe-bottom)) calc(16px + var(--app-safe-left)); text-align: center; }
.error-page h1 { font-size: 120px; color: #e1e3e6; margin: 0; }
.error-page p { font-size: 18px; color: #555b63; margin: 20px 0; }
</style>
