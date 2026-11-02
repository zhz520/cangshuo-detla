<template>
  <view class="directory-page tab-page">
    <view class="search-bar">
      <input v-model="keyword" maxlength="50" placeholder="搜索打手昵称" confirm-type="search" @confirm="refresh" />
      <text @click="refresh">搜索</text>
    </view>
    <scroll-view scroll-x class="category-tabs" :show-scrollbar="false">
      <view class="tabs-inner">
        <text class="level-item" :class="{active:selectedLevel===null}" @click="selectLevel(null)">全部打手</text>
        <text v-for="level in levels" :key="level.id" class="level-item" :class="{active:selectedLevel===level.id}" @click="selectLevel(level.id)">{{ level.name }}</text>
      </view>
    </scroll-view>
    <scroll-view scroll-y class="directory-scroll" @scrolltolower="loadMore">
      <view v-if="loading && !players.length" class="hint">加载中...</view>
      <view v-else-if="loadError" class="hint" @click="refresh">加载失败，点击重试</view>
      <template v-if="!loadError">
        <view class="result-heading">
          <text>{{ selectedName }}</text>
          <text>{{ total }} 位打手</text>
        </view>
        <view class="player-grid">
          <PlayerShowcaseCard v-for="player in players" :key="player.id" :player="player" />
        </view>
        <EmptyState v-if="!loading && !loadError && !players.length" text="暂无符合条件的打手" />
        <view v-if="loading" class="hint">加载中...</view>
        <view v-else-if="finished && players.length" class="hint">没有更多了</view>
      </template>
      <view class="bottom-space" />
    </scroll-view>
    <CustomTabBar :current="2" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import PlayerShowcaseCard from '@/components/PlayerShowcaseCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getPlayerCategories, getPublicPlayers } from '@/api/player'

const levels = ref([])
const players = ref([])
const keyword = ref('')
const selectedLevel = ref(null)
const loading = ref(false)
const loadError = ref(false)
const finished = ref(false)
const total = ref(0)
let generation = 0
let pageNum = 1

const selectedName = computed(() => {
  if (keyword.value.trim()) return '搜索结果'
  return levels.value.find(level => level.id === selectedLevel.value)?.name || '全部打手'
})

onShow(async () => {
  try {
    levels.value = (await getPlayerCategories()).data || []
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
.directory-page{height:var(--app-page-height, 100vh);background:#f7f8f9;display:flex;flex-direction:column}
.search-bar{flex-shrink:0;padding:20rpx 24rpx;display:flex;align-items:center;gap:20rpx;background:#fff;input{flex:1;min-width:0;background:#f4f5f6;border-radius:999rpx;height:68rpx;padding:0 26rpx;font-size:26rpx}text{font-size:26rpx;font-weight:600}}
.category-tabs{background:#fff;flex-shrink:0;width:100%;border-bottom:1rpx solid #eeeff1}
.tabs-inner{width:max-content;min-width:100%;box-sizing:border-box;display:flex;gap:14rpx;padding:8rpx 24rpx 20rpx;white-space:nowrap}
.level-item{display:block;flex-shrink:0;white-space:nowrap;padding:14rpx 24rpx;font-size:25rpx;color:#73777e;border-radius:999rpx;&.active{background:#17191c;color:#fff;font-weight:600}}
.directory-scroll{flex:1;height:0;padding:0 24rpx;box-sizing:border-box}
.player-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16rpx}
.hint{display:block;text-align:center;padding:40rpx 0;color:#969aa1;font-size:24rpx}
.result-heading{display:flex;justify-content:space-between;padding:28rpx 0;font-size:28rpx;font-weight:700;text:last-child{font-size:24rpx;color:#858992;font-weight:400}}
.bottom-space{height:calc(140rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)))}
</style>
