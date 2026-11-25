<template>
  <view class="custom-tabbar" :style="{ paddingBottom: safeBottom + 'px' }">
    <view class="tabbar-content">
      <view v-if="currentIndex >= 0" class="tabbar-pill"
        :class="{ 'is-flowing': pillFlowing }" :style="pillInitialStyle" aria-hidden="true" />
      <view
        v-for="(tab, index) in currentTabs"
        :key="tab.pagePath"
        class="tabbar-item"
        :class="{ active: currentIndex === index }"
        @click="switchTab(tab, index)"
      >
        <view class="tabbar-icon">
          <AppImage class="icon-img" :src="currentIndex === index ? tab.iconActive : tab.icon" mode="aspectFit" :eager="true" />
          <view v-if="tabBadgeCount(tab) > 0" class="badge">
            {{ tabBadgeCount(tab) > 99 ? '99+' : tabBadgeCount(tab) }}
          </view>
          <view v-else-if="tabRedDot(tab)" class="red-dot" />
        </view>
        <text class="tabbar-text">{{ tab.text }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref, computed, nextTick, onMounted, onUnmounted, watch } from 'vue'
import { onShow, onHide } from '@dcloudio/uni-app'
import { useAppStore } from '@/store/app'
import { useRemindStore } from '@/store/remind'
import { useSiteStore } from '@/store/site'
import { ROLE_TABS } from '@/utils/role'
import { holdTabbarScrollLock, releaseTabbarScrollLock } from '@/utils/tabbar-scroll-lock'
import { rememberTabMotion, takeTabMotion } from '@/utils/tabbar-motion-state'
// #ifdef H5
import { navigateTabWithTransition } from '@/utils/page-motion.mjs'
// #endif

const props = defineProps({
  current: { type: Number, default: 0 }
})

const appStore = useAppStore()
const remindStore = useRemindStore()
const siteStore = useSiteStore()
const safeBottom = ref(0)
const initialMotion = takeTabMotion()
let navigating = false
let flowTimer
let scrollLocked = false

const baseTabs = computed(() => ROLE_TABS[appStore.role] || ROLE_TABS.user)
const currentTabs = computed(() => {
  if (appStore.role === 'user') {
    return baseTabs.value.filter(tab =>
      (!siteStore.externalCustomerServiceEnabled || tab.pagePath !== '/pages/chat/list') &&
      (siteStore.playerDirectoryEnabled || tab.pagePath !== '/pages/players/index')
    )
  }
  return baseTabs.value
})
const currentIndex = computed(() => {
  const requestedPath = baseTabs.value[props.current]?.pagePath
  return currentTabs.value.findIndex(tab => tab.pagePath === requestedPath)
})
const displayIndex = ref(initialMotion?.from ?? Math.max(0, currentIndex.value))
const pillFlowing = ref(false)
const pillInitialStyle = computed(() => {
  const count = Math.max(1, currentTabs.value.length)
  const index = Math.min(count - 1, Math.max(0, displayIndex.value))
  return {
    left: ((index * 100 + 10) / count) + '%',
    width: (80 / count) + '%'
  }
})

/** 我的 tab 是否有系统消息未读（显示小红点） */
const mineRedDot = computed(() => {
  const role = appStore.role
  return remindStore.systemUnread > 0 || (role !== 'cs' && remindStore.workbenchUnread > 0)
})

function badgeCount(tab) {
  const role = appStore.role
  if (tab.text === '消息') {
    return remindStore.messageUnread
  }
  if (role === 'cs' && tab.text === '投诉') return remindStore.complaintUnread
  return 0
}

function tabBadgeCount(tab) {
  return badgeCount(tab)
}

function tabRedDot(tab) {
  if (tab.text === '我的') return mineRedDot.value
  if (appStore.role === 'cs' && tab.text === '工作台') return remindStore.workbenchUnread > 0
  return false
}

onMounted(() => {
  const windowInfo = uni.getWindowInfo()
  safeBottom.value = windowInfo.safeAreaInsets?.bottom || 0
  acquireScrollLock()
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (currentIndex.value >= 0 && displayIndex.value !== currentIndex.value) {
      startPillFlow()
      displayIndex.value = currentIndex.value
    }
  }))
})

watch(() => [currentIndex.value, currentTabs.value.length], () => {
  if (currentIndex.value < 0) return
  nextTick(() => {
    if (displayIndex.value !== currentIndex.value) {
      startPillFlow()
      displayIndex.value = currentIndex.value
    }
  })
})

onShow(() => {
  acquireScrollLock()
})

onHide(() => {
  releaseScrollLock()
})

onUnmounted(() => {
  releaseScrollLock()
})

function acquireScrollLock() {
  if (scrollLocked) return
  scrollLocked = true
  // #ifdef H5
  holdTabbarScrollLock()
  // #endif
}

function releaseScrollLock() {
  if (!scrollLocked) return
  scrollLocked = false
  // #ifdef H5
  releaseTabbarScrollLock()
  // #endif
}

const TAB_BAR_PAGES = [
  '/pages/index/index',
  '/pages/category/index',
  '/pages/players/index',
  '/pages/chat/list',
  '/pages/mine/index'
]

function switchTab(tab, index) {
  if (currentIndex.value === index || navigating) return
  navigating = true
  const complete = () => { navigating = false }
  const direction = index > currentIndex.value ? 'forward' : 'back'
  // #ifdef H5
  rememberTabMotion(currentIndex.value, direction)
  // #endif
  if (TAB_BAR_PAGES.includes(tab.pagePath)) {
    // #ifdef H5
    const transition = navigateTabWithTransition(() => new Promise(resolve => {
      uni.switchTab({
        url: tab.pagePath,
        complete: () => {
          complete()
          resolve()
        }
      })
    }), document, window, { direction })
    transition.settled.catch(() => {}).finally(complete)
    // #endif
    // #ifndef H5
    uni.switchTab({ url: tab.pagePath, complete })
    // #endif
  } else {
    uni.redirectTo({ url: tab.pagePath, complete })
  }
}

function startPillFlow() {
  if (flowTimer) clearTimeout(flowTimer)
  pillFlowing.value = false
  nextTick(() => {
    pillFlowing.value = true
    flowTimer = setTimeout(() => { pillFlowing.value = false }, 440)
  })
}
</script>

<style lang="scss" scoped>
.custom-tabbar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 110rpx;
  box-sizing: content-box;
  background: var(--store-surface);
  border-top: 1rpx solid var(--store-line);
  box-shadow: none;
  z-index: 999;
}

.tabbar-content {
  position: relative;
  display: flex;
  align-items: center;
  height: 110rpx;
}

.tabbar-pill {
  position: absolute;
  bottom: 10rpx;
  height: 84rpx;
  border-radius: 28rpx;
  background: rgba(23, 25, 28, .075);
  pointer-events: none;
  transform-origin: center;
  z-index: 0;
  transition: left 360ms cubic-bezier(.18,.82,.24,1.04), width 420ms cubic-bezier(.22,.75,.2,1.03);

  &.is-flowing { animation: tab-pill-flow 420ms cubic-bezier(.2,.8,.2,1) both; }
}
@supports (background: color-mix(in srgb, white 10%, transparent)) {
  .tabbar-pill { background: color-mix(in srgb, var(--store-text) 10%, transparent); }
}
html[data-theme='dark'] .tabbar-pill { background: rgba(255,255,255,.12); }
html[data-theme='neumorphism'] .tabbar-pill { background: var(--store-soft); box-shadow: inset 0 2rpx 5rpx rgba(255,255,255,.7), 0 2rpx 6rpx rgba(23,25,28,.08); }

.tabbar-item {
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;

  .tabbar-icon {
    position: relative;

    .icon-img {
      width: 48rpx;
      height: 48rpx;
      background-color: transparent !important;
    }

    .badge {
      position: absolute;
      top: -10rpx;
      right: -20rpx;
      min-width: 32rpx;
      height: 32rpx;
      line-height: 32rpx;
      padding: 0 8rpx;
      font-size: 20rpx;
      color: #fff;
      background: #d44955;
      border-radius: 32rpx;
      text-align: center;
    }
    .red-dot {
      position: absolute;
      top: -4rpx;
      right: -4rpx;
      width: 16rpx;
      height: 16rpx;
      background: #d44955;
      border-radius: 50%;
      border: 2rpx solid #fff;
    }
  }

  .tabbar-text {
    font-size: 20rpx;
    color: var(--store-muted);
    margin-top: 4rpx;
    transition: color 320ms cubic-bezier(.2,.7,.2,1);
  }

  &.active {
    .tabbar-icon {
      color: var(--store-text);
      animation: tab-icon-spring 420ms cubic-bezier(.2,.8,.2,1) both;
    }
    .tabbar-text {
      color: var(--store-text);
      font-weight: bold;
      animation: tab-text-activate 360ms cubic-bezier(.2,.7,.2,1) both;
    }
  }
}

@keyframes tab-text-activate {
  from { color: var(--store-muted); }
  to { color: var(--store-text); }
}
@keyframes tab-icon-spring {
  0% { transform: scale(.72); }
  55% { transform: scale(1.18); }
  82% { transform: scale(.95); }
  100% { transform: scale(1); }
}
@keyframes tab-pill-flow {
  0% { transform: scaleX(1) scaleY(1); border-radius: 28rpx; }
  34% { transform: scaleX(1.16) scaleY(.86); border-radius: 36rpx; }
  72% { transform: scaleX(.96) scaleY(1.06); border-radius: 26rpx; }
  100% { transform: scaleX(1) scaleY(1); border-radius: 28rpx; }
}
</style>
