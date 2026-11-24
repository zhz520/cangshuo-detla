<template>
  <view class="custom-tabbar" :style="{ paddingBottom: safeBottom + 'px' }">
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
</template>

<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { onShow, onHide } from '@dcloudio/uni-app'
import { useAppStore } from '@/store/app'
import { useRemindStore } from '@/store/remind'
import { useSiteStore } from '@/store/site'
import { ROLE_TABS } from '@/utils/role'

const props = defineProps({
  current: { type: Number, default: 0 }
})

const appStore = useAppStore()
const remindStore = useRemindStore()
const siteStore = useSiteStore()
const safeBottom = ref(0)
let navigating = false

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
  lockH5PageScroll()
})

onShow(() => {
  lockH5PageScroll()
})

onHide(() => {
  unlockH5PageScroll()
})

onUnmounted(() => {
  unlockH5PageScroll()
})

function lockH5PageScroll() {
  // #ifdef H5
  document.documentElement.classList.add('tabbar-page-locked')
  document.body.classList.add('tabbar-page-locked')
  // #endif
}

function unlockH5PageScroll() {
  // #ifdef H5
  document.documentElement.classList.remove('tabbar-page-locked')
  document.body.classList.remove('tabbar-page-locked')
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
  if (TAB_BAR_PAGES.includes(tab.pagePath)) {
    uni.switchTab({ url: tab.pagePath, complete })
  } else {
    uni.redirectTo({ url: tab.pagePath, complete })
  }
}
</script>

<style lang="scss" scoped>
.custom-tabbar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  height: 110rpx;
  box-sizing: content-box;
  background: var(--store-surface);
  border-top: 1rpx solid var(--store-line);
  box-shadow: none;
  z-index: 999;
}

.tabbar-item {
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
  }

  &.active {
    .tabbar-icon {
      color: var(--store-text);
    }
    .tabbar-text {
      color: var(--store-text);
      font-weight: bold;
    }
  }
}
</style>
