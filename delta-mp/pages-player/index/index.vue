<template>
  <view class="player-home tab-page">
    <scroll-view scroll-y class="player-home-scroll tab-page-scroll" :show-scrollbar="false">
      <PageState :loading="loading && !loaded" :error="error" @retry="load" />
      <PlayerOverview v-if="loaded" :data="homeData" :profile="playerStore.playerInfo" @navigate="go" />
      <!-- 待处理订单预览 -->
      <view class="section" v-if="recentOrders.length">
        <view class="section-header">
          <text class="section-title">待处理订单</text>
          <text class="more" @click="go('/pages-player/order/list')">查看全部 ></text>
        </view>
        <OrderCard v-for="o in recentOrders" :key="o.id" :order="o" detail-path="/pages-player/order/detail" />
      </view>
      <view class="tab-page-bottom-spacer" />
    </scroll-view>
    <CustomTabBar :current="0" />
  </view>
</template>
<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import OrderCard from '@/components/OrderCard.vue'
import { getPlayerHome, getMyWork } from '@/api/player'
import PlayerOverview from '@/components/PlayerOverview.vue'
import PageState from '@/components/PageState.vue'
import { usePlayerStore } from '@/store/player'

const homeData = ref({})
const recentOrders = ref([])

const playerStore = usePlayerStore()
const loading = ref(false), loaded = ref(false), error = ref('')
onShow(load)
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  const [home, work] = await Promise.allSettled([getPlayerHome({ loading: false }), getMyWork({ pageNum: 1, pageSize: 3, status: 'ASSIGNED' }), playerStore.fetchProfile()])
  if (home.status === 'fulfilled') { homeData.value = home.value.data || {}; loaded.value = true }
  else error.value = '工作台数据暂未获取，请稍后重试。'
  if (work.status === 'fulfilled') recentOrders.value = work.value.data?.records || []
  loading.value = false
}

function go(url) { if (['/pages-player/hall/index', '/pages-player/order/list'].includes(url)) uni.redirectTo({ url }); else uni.navigateTo({ url }) }
</script>
<style lang="scss" scoped>
.player-home { background: #f7f7f7; position: relative; }
.stat-cards { display: flex; gap: 20rpx; padding: 24rpx;
  .card { flex: 1; background: rgba(0,0,0,0.04); padding: 24rpx; border-radius: 12rpx; text-align: center;
    .num { font-size: 36rpx; font-weight: bold; color: #111111; display: block; }
    .label { font-size: 24rpx; color: rgba(0,0,0,0.3); margin-top: 8rpx; display: block; }
  }
}
.quick-actions { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20rpx; padding: 0 24rpx;
  .action { background: rgba(0,0,0,0.04); padding: 24rpx 0; text-align: center; border-radius: 12rpx; font-size: 24rpx; color: rgba(0,0,0,0.85); position: relative; display: flex; flex-direction: column; align-items: center; gap: 8rpx; .action-icon { width: 48rpx; height: 48rpx; } }
  .pending-badge { position: absolute; top: 8rpx; right: 8rpx; min-width: 32rpx; height: 32rpx; line-height: 32rpx; padding: 0 8rpx; font-size: 20rpx; color: #fff; background: #d44955; border-radius: 32rpx; text-align: center; }
}
.section { padding: 24rpx; }
.section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16rpx; .section-title { font-size: 28rpx; font-weight: bold; } .more { font-size: 24rpx; color: rgba(0,0,0,0.3); } }
</style>
