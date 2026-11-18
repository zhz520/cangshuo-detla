<template>
  <view class="review-page">
    <scroll-view scroll-y class="list" @scrolltolower="loadMore">
      <view v-for="r in reviews" :key="r.id" class="review-card">
        <view class="card-top">
          <AppImage :width="160" class="avatar" :src="r.userAvatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
          <view class="user-info">
            <text class="name">{{ r.userNickname || '匿名用户' }}</text>
            <text class="time">{{ r.createdAt }}</text>
          </view>
          <StarRating :model-value="r.rating" readonly />
        </view>
        <text class="content">{{ r.content }}</text>
        <view v-if="r.imageUrls.length" class="images">
          <AppImage v-for="(img, i) in r.imageUrls" :key="i" :src="img" mode="aspectFill" class="img" @click="previewImg(r.imageUrls, i)" />
        </view>
        <view class="order-info">
          <text class="order-label">订单：{{ r.orderNo || r.orderId }}</text>
          <text class="product">{{ r.productName }}</text>
        </view>
        <!-- 接单员回复 -->
        <view v-if="r.reply" class="reply-box">
          <text class="reply-label">我的回复：</text>
          <text class="reply-content">{{ r.reply }}</text>
        </view>
      </view>
      <PageState :loading="loading && !reviews.length" :error="error" :empty="!reviews.length" empty-text="暂无评价" @retry="retry" />
      <view v-if="reviews.length && loading" class="loading-tip">正在加载…</view>
      <view v-else-if="reviews.length && finished && !error" class="loading-tip">已显示全部评价</view>
    </scroll-view>
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import StarRating from '@/components/StarRating.vue'
import { getReviewsByPlayer } from '@/api/review'
import { getPlayerProfile } from '@/api/player'
import { usePagedList } from '@/composables/usePagedList'

let playerId
const { items: reviews, loading, error, finished, refresh, loadMore, retry, cancel, dispose } = usePagedList({
  pageSize: 20,
  async fetchPage({ pageNum, pageSize, isCurrent }) {
    if (playerId == null) {
      const profile = await getPlayerProfile({ loading: false, silent: true })
      if (!isCurrent()) return { records: [] }
      if (profile.data?.id == null) throw new Error('接单员资料异常，请重试')
      playerId = profile.data.id
    }
    const { data } = await getReviewsByPlayer(playerId, { pageNum, pageSize }, { role: 'player', loading: false, silent: true })
    if (!Array.isArray(data?.records)) throw new Error('评价数据异常，请重试')
    return { ...data, records: data.records.map(row => ({ ...row, imageUrls: imageUrls(row.images) })) }
  }
})
onShow(() => refresh())
onHide(cancel)
onUnload(dispose)
function imageUrls(value) {
  if (Array.isArray(value)) return value.filter(url => typeof url === 'string' && url.trim())
  if (typeof value !== 'string' || !value.trim()) return []
  try { const parsed = JSON.parse(value); if (Array.isArray(parsed)) return imageUrls(parsed) } catch (_) {}
  return value.split(',').map(url => url.trim()).filter(Boolean)
}
function previewImg(imgs, idx) { uni.previewImage({ urls: imgs, current: imgs[idx] }) }
</script>
<style lang="scss" scoped>
.review-page { background: var(--store-soft); min-height: var(--app-page-height, 100vh); }
.list { height: var(--app-page-height, 100vh); box-sizing: border-box; padding: 20rpx 24rpx calc(24rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); }
.review-card { background: var(--store-surface); border: 1rpx solid var(--store-line); border-radius: 20rpx; padding: 24rpx; margin-bottom: 16rpx;
  .card-top { display: flex; align-items: center; gap: 12rpx; margin-bottom: 16rpx; }
  .avatar { width: 64rpx; height: 64rpx; border-radius: 50%; }
  .user-info { flex: 1; .name { font-size: 26rpx; font-weight: bold; display: block; } .time { font-size: 22rpx; color: var(--store-regular); display: block; } }
  .content { font-size: 28rpx; color: var(--store-text); line-height: 1.6; display: block; margin-bottom: 12rpx; }
  .images { display: flex; flex-wrap: wrap; gap: 12rpx; margin-bottom: 12rpx; .img { width: 160rpx; height: 160rpx; border-radius: 8rpx; } }
  .order-info { font-size: 22rpx; color: var(--store-regular); padding: 12rpx 0; border-top: 1rpx solid #f7f7f7; .order-label { margin-right: 16rpx; } }
  .reply-box { background: var(--store-soft); padding: 16rpx; border-radius: 8rpx; margin-top: 12rpx;
    .reply-label { font-size: 24rpx; color: var(--store-text); } .reply-content { font-size: 24rpx; color: var(--store-regular); }
  }
}
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: var(--store-regular); }
</style>
