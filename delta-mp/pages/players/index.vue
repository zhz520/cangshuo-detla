<template>
  <view class="directory-page tab-page">
    <ScreenHeader title="打手">
      <view class="screen-icon-btn" role="button" aria-label="搜索打手" @click="toggleSearch">
        <image src="/static/icons/header-search.svg" mode="aspectFit" />
      </view>
    </ScreenHeader>
    <view v-if="siteStore.themeId === 'neumorphism'" class="neo-directory-head">
      <text class="neo-hero-kicker">TEAM / COMMUNITY</text>
      <text class="neo-hero-title">{{ siteStore.siteName }}</text>
      <text class="neo-hero-sub">找一位默契的队友，开始这一单</text>
    </view>
    <view v-if="searchOpen" class="search-bar">
      <input v-model="keyword" maxlength="50" placeholder="搜索打手昵称" confirm-type="search" @confirm="refresh" />
      <text @click="refresh">搜索</text>
    </view>
    <scroll-view scroll-x class="category-tabs" :show-scrollbar="false">
      <view class="tabs-inner">
        <text class="level-item" :class="{active:selectedLevel===null}" @click="selectLevel(null)">全部打手</text>
        <text v-for="level in levels" :key="level.id" class="level-item" :class="{active:selectedLevel===level.id}" @click="selectLevel(level.id)">{{ level.name }}</text>
      </view>
    </scroll-view>
    <view class="directory-filters">
      <picker :range="sortOptions" :value="sortIndex" @change="onSortChange">
        <view class="filter-pill"><text>{{ sortOptions[sortIndex] }}</text><text class="filter-chevron">⌄</text></view>
      </picker>
      <picker :range="levelOptions" range-key="name" :value="levelIndex" @change="onLevelChange">
        <view class="filter-pill"><text>{{ levelOptions[levelIndex]?.name || '全部段位' }}</text><text class="filter-chevron">⌄</text></view>
      </picker>
      <picker :range="onlineOptions" :value="onlineIndex" @change="onOnlineChange">
        <view class="filter-pill"><text>{{ onlineOptions[onlineIndex] }}</text><text class="filter-chevron">⌄</text></view>
      </picker>
      <text class="filter-total">{{ total }} 位</text>
    </view>
    <scroll-view scroll-y class="directory-scroll" @scrolltolower="loadMore">
      <view class="directory-content">
      <view v-if="loading && !players.length" class="hint">加载中...</view>
      <view v-else-if="loadError" class="hint" @click="refresh">加载失败，点击重试</view>
      <template v-if="!loadError">
        <view class="result-heading">
          <text>{{ selectedName }}</text>
          <text>{{ total }} 位打手</text>
        </view>
        <view v-if="layout==='grid'" class="player-grid">
          <PlayerShowcaseCard v-for="player in visiblePlayers" :key="player.id" :player="player" />
        </view>
        <view v-if="layout!=='grid'" class="player-list" :class="{ compact: layout==='compact' }">
          <PlayerListCard v-for="player in visiblePlayers" :key="player.id" :player="player" :compact="layout==='compact'" />
        </view>
        <EmptyState v-if="!loading && !loadError && !visiblePlayers.length" text="暂无符合条件的打手" />
        <view v-if="loading" class="hint">加载中...</view>
        <view v-else-if="finished && players.length" class="hint">没有更多了</view>
      </template>
      <view class="bottom-space" />
      </view>
    </scroll-view>
    <CustomTabBar :current="2" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import ScreenHeader from '@/components/ScreenHeader.vue'
import PlayerShowcaseCard from '@/components/PlayerShowcaseCard.vue'
import PlayerListCard from '@/components/PlayerListCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getPlayerCategories, getPublicPlayerLayout, getPublicPlayers } from '@/api/player'
import { useSiteStore } from '@/store/site'
const siteStore = useSiteStore()

const levels = ref([])
const players = ref([])
const keyword = ref('')
const selectedLevel = ref(null)
const loading = ref(false)
const loadError = ref(false)
const finished = ref(false)
const total = ref(0)
const layout = ref('grid')
const searchOpen = ref(false)
const sortOptions = ['综合排序', '评分优先', '接单最多']
const sortMode = ref('default')
const onlineOptions = ['全部状态', '只看在线']
const onlineOnly = ref(false)
const sortIndex = computed(() => Math.max(0, ['default', 'rating', 'orders'].indexOf(sortMode.value)))
const onlineIndex = computed(() => (onlineOnly.value ? 1 : 0))
const levelOptions = computed(() => [{ id: null, name: '全部段位' }, ...levels.value])
const levelIndex = computed(() => {
  const index = levelOptions.value.findIndex(level => level.id === selectedLevel.value)
  return index < 0 ? 0 : index
})
const visiblePlayers = computed(() => {
  const list = onlineOnly.value ? players.value.filter(player => player.isOnline === 1) : players.value.slice()
  if (sortMode.value === 'rating') {
    list.sort((left, right) => Number(right.avgRating || 0) - Number(left.avgRating || 0))
  } else if (sortMode.value === 'orders') {
    list.sort((left, right) => Number(right.orderCount || 0) - Number(left.orderCount || 0))
  }
  return list
})
let generation = 0
let pageNum = 1
let closedRedirect = false

const selectedName = computed(() => {
  if (keyword.value.trim()) return '搜索结果'
  return levels.value.find(level => level.id === selectedLevel.value)?.name || '全部打手'
})

onShow(async () => {
  await siteStore.fetchSiteConfig()
  if (!siteStore.playerDirectoryEnabled) {
    if (!closedRedirect) {
      closedRedirect = true
      uni.showToast({ title: '打手页面暂未开放', icon: 'none' })
      setTimeout(() => uni.switchTab({ url: '/pages/index/index' }), 500)
    }
    return
  }
  closedRedirect = false
  try {
    const [levelResult, layoutResult] = await Promise.allSettled([getPlayerCategories(), getPublicPlayerLayout()])
    if (levelResult.status === 'fulfilled') levels.value = levelResult.value.data || []
    if (layoutResult.status === 'fulfilled' && ['grid', 'list', 'compact'].includes(layoutResult.value.data?.layout)) {
      layout.value = layoutResult.value.data.layout
    }
    if (selectedLevel.value !== null && !levels.value.some(level => level.id === selectedLevel.value)) {
      selectedLevel.value = null
    }
  } catch (_) {
    loadError.value = true
  }
  refresh()
})

function selectLevel(id) {
  selectedLevel.value = id
  refresh()
}

function toggleSearch() {
  searchOpen.value = !searchOpen.value
}

function onSortChange(event) {
  sortMode.value = ['default', 'rating', 'orders'][Number(event.detail.value)] || 'default'
}

function onLevelChange(event) {
  const level = levelOptions.value[Number(event.detail.value)]
  selectedLevel.value = level?.id ?? null
  refresh()
}

function onOnlineChange(event) {
  onlineOnly.value = Number(event.detail.value) === 1
}

function refresh() {
  generation++
  pageNum = 1
  players.value = []
  finished.value = false
  loading.value = false
  loadPlayers()
}

async function loadPlayers() {
  if (loading.value || finished.value) return
  const current = generation
  loading.value = true
  loadError.value = false
  try {
    const params = { keyword: keyword.value.trim(), pageNum, pageSize: 12 }
    if (selectedLevel.value !== null) params.level = selectedLevel.value
    const { data } = await getPublicPlayers(params)
    if (current !== generation) return
    const records = data?.records || []
    players.value.push(...records)
    total.value = Number(data?.total || 0)
    finished.value = records.length < 12 || players.value.length >= total.value
    pageNum++
  } catch (_) {
    if (current === generation) loadError.value = true
  } finally {
    if (current === generation) loading.value = false
  }
}

function loadMore() {
  loadPlayers()
}
</script>

<style lang="scss" scoped>
.directory-page {
  height: var(--app-page-height, 100vh);
  min-height: var(--app-page-height, 100vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--store-soft);
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 0 24rpx 12rpx;
  flex-shrink: 0;
  input {
    flex: 1;
    min-width: 0;
    height: 70rpx;
    padding: 0 24rpx;
    border-radius: 999rpx;
    background: var(--store-surface);
    color: var(--store-text);
    font-size: 24rpx;
  }
  text {
    flex-shrink: 0;
    color: var(--store-text);
    font-size: 23rpx;
    font-weight: 750;
  }
}

.category-tabs {
  width: 100%;
  flex-shrink: 0;
  background: transparent;
}
.tabs-inner {
  display: flex;
  gap: 10rpx;
  width: max-content;
  min-width: 100%;
  padding: 2rpx 24rpx 12rpx;
  box-sizing: border-box;
  white-space: nowrap;
}
.level-item {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  padding: 12rpx 22rpx;
  border: 1rpx solid transparent;
  border-radius: 999rpx;
  color: var(--store-regular);
  font-size: 22rpx;
  white-space: nowrap;
  &.active {
    border-color: var(--store-text);
    background: var(--store-primary);
    color: #ffffff;
    font-weight: 750;
  }
}

.directory-filters {
  display: flex;
  align-items: center;
  gap: 9rpx;
  padding: 0 24rpx 14rpx;
  flex-shrink: 0;
}
.filter-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7rpx;
  height: 56rpx;
  padding: 0 15rpx;
  border-radius: 999rpx;
  background: var(--store-surface);
  color: var(--store-regular);
  font-size: 19rpx;
  white-space: nowrap;
}
.filter-chevron { color: var(--store-muted); font-size: 16rpx; }
.filter-total {
  margin-left: auto;
  color: var(--store-muted);
  font-size: 18rpx;
  white-space: nowrap;
}

.directory-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 0 24rpx;
  box-sizing: border-box;
}
.directory-content { padding-bottom: 4rpx; }
.result-heading { display: none; }
.player-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}
.player-list {
  display: grid;
  gap: 14rpx;
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.player-list :deep(.player-list-card) {
  border: 0 !important;
  border-radius: 24rpx !important;
  border-bottom: 0 !important;
  box-shadow: none !important;
}
.player-list :deep(.player-list-card:last-child) { border-bottom: 0 !important; }
.player-list.compact { gap: 10rpx; }
.hint {
  display: block;
  padding: 38rpx 0;
  color: var(--store-muted);
  font-size: 23rpx;
  text-align: center;
}
.bottom-space { height: calc(132rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); }
</style>
