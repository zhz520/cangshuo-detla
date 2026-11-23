<template>
  <div v-if="progressVisible" class="app-loading" role="status" aria-live="polite">
    <span class="app-loading__bar" aria-hidden="true" />
    <span class="sr-only">{{ loadingFeedback.destination ? '正在切换页面' : '正在加载数据' }}</span>
  </div>
  <div v-if="loadingFeedback.routeError" class="app-route-error" role="alert">
    <span>页面加载失败，请检查网络后重试。</span>
    <el-button size="small" type="primary" @click="retryNavigation">重试加载</el-button>
    <el-button size="small" @click="reloadDestination">刷新应用</el-button>
    <el-button size="small" text @click="loadingFeedback.routeError = null">关闭</el-button>
  </div>
  <router-view v-slot="{ Component, route: pageRoute }">
    <PageViewport v-if="Component" shell :path="pageRoute.path">
      <component :is="Component" />
    </PageViewport>
  </router-view>
  <div v-if="siteStore.error" class="site-config-error" role="status">
    <span>{{ siteStore.error }}</span>
    <el-button text size="small" :loading="siteStore.loading" @click="siteStore.fetchSiteConfig(true)">重试</el-button>
  </div>
</template>

<script setup>
import { useDelayedBusy } from '@/composables/useDelayedBusy'
import PageViewport from '@/components/PageViewport.vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loadingFeedback } from '@/utils/loading-feedback'
import { useSiteStore } from '@/stores/site'
import { getPublicTheme } from '@/api/platform'
const siteStore = useSiteStore()
const route = useRoute()
const router = useRouter()
const busy = computed(() => Boolean(loadingFeedback.destination) || loadingFeedback.requests > 0)
const delayedBusy = useDelayedBusy(busy)
const regionalBusy = ref(false)
const progressVisible = computed(() => delayedBusy.value && (Boolean(loadingFeedback.destination) || !regionalBusy.value))
// Older views use Element Plus' DOM directive; watch its actual visibility,
// not a list of route names. Route progress and error alerts have independent owners.
watch(() => delayedBusy.value && !loadingFeedback.destination, (check, _, onCleanup) => {
  if (!check) { regionalBusy.value = false; return }
  const root = document.getElementById('app')
  const update = () => {
    const cues = root.querySelectorAll('.el-loading-mask, .async-state-skeleton, .load-feedback[role="status"]')
    regionalBusy.value = Array.from(cues).some(el => el.getClientRects().length && getComputedStyle(el).visibility === 'visible')
  }
  update()
  const observer = new MutationObserver(update)
  observer.observe(root, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style', 'hidden', 'role'] })
  onCleanup(() => observer.disconnect())
})
async function applyTheme() {
  try {
    const response = await getPublicTheme()
    const themeId = response.data?.id
    if (['neumorphism', 'dark'].includes(themeId)) document.documentElement.setAttribute('data-theme', themeId)
    else document.documentElement.removeAttribute('data-theme')
  } catch (_) {
    document.documentElement.removeAttribute('data-theme')
  }
}
watch(() => [route.meta.title, siteStore.adminTitle], ([title, brand]) => {
  document.title = title ? `${title} - ${brand}` : brand
}, { immediate: true })
onUnmounted(() => {
  window.removeEventListener('delta:theme-changed', applyTheme)
})
onMounted(() => { applyTheme(); window.addEventListener('delta:theme-changed', applyTheme) })

function retryNavigation() {
  const target = loadingFeedback.routeError?.path
  if (target) router.replace(target).catch(() => {})
}
function reloadDestination() {
  window.location.assign(router.resolve(loadingFeedback.routeError?.path || route.fullPath).href)
}
siteStore.fetchSiteConfig()
</script>
