<template>
  <view class="product-card" :class="{ dense }" @click="goDetail">
    <AppImage
      class="cover"
      :src="product.coverImage"
      :mode="coverMode"
      fallback="/static/icons/图片.svg"
      :style="{ height: coverHeight + 'rpx' }"
    />
    <view class="info">
      <text class="name text-ellipsis-2">{{ product.name }}</text>
      <view v-if="product.subtitle" class="subtitle-wrap">
        <text class="subtitle text-ellipsis">{{ product.subtitle }}</text>
      </view>
      <view class="bottom">
        <text class="price">¥{{ displayPrice }}</text>
        <view class="go-arrow" aria-hidden="true">
          <image src="/static/icons/arrow-right.svg" mode="aspectFit" />
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import AppImage from '@/components/AppImage.vue'
import { computed } from 'vue'

const props = defineProps({
  product: { type: Object, required: true },
  coverHeight: { type: Number, default: 220 },
  coverMode: { type: String, default: 'aspectFill' },
  dense: { type: Boolean, default: false },
  designatedPlayerId: { type: [String,Number], default: '' }
})

const displayPrice = computed(() => {
  return Number(props.product.price || 0).toFixed(2)
})

function goDetail() {
  const suffix = props.designatedPlayerId ? '&playerId=' + props.designatedPlayerId : ''
  uni.navigateTo({ url: '/pages/product/detail?id=' + props.product.id + suffix })
}
</script>

<style lang="scss" scoped>
.product-card {
  min-width: 0;
  overflow: hidden;
  border: 0;
  border-radius: 24rpx;
  background: #ffffff;
  box-shadow: none;

  .cover {
    display: block;
    width: 100%;
    background: #f1f2f4;
  }

  .info {
    padding: 18rpx 18rpx 20rpx;
    overflow: hidden;
    min-width: 0;

    .name {
      display: -webkit-box;
      overflow: hidden;
      color: #111111;
      font-size: 26rpx;
      font-weight: 800;
      line-height: 1.3;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    .subtitle-wrap {
      min-width: 0;
      overflow: hidden;
      margin-top: 6rpx;
    }
    .subtitle {
      display: block;
      color: #777777;
      font-size: 21rpx;
      line-height: 1.4;
    }

    .bottom {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10rpx;
      margin-top: 14rpx;
    }
    .price {
      color: #111111;
      font-size: 32rpx;
      font-weight: 850;
      line-height: 1;
      font-variant-numeric: tabular-nums;
    }
    .go-arrow {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 44rpx;
      height: 44rpx;
      flex-shrink: 0;
      border-radius: 50%;
      background: #f3f4f5;
      image { width: 22rpx; height: 22rpx; }
    }
  }
}

.product-card.dense .info { padding: 12rpx 14rpx 14rpx; }
</style>
