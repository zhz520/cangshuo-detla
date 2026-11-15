<template>
  <view class="chat-bubble" :class="{ 'is-self': isSelf }">
    <AppImage
      v-if="isSelf"
      :width="160"
      class="avatar"
      :src="selfAvatar"
      fallback="/static/images/delta-default-avatar.png"
      mode="aspectFill"
    />
    <AppImage
      v-else
      :width="160"
      class="avatar"
      :src="avatar"
      fallback="/static/images/delta-default-avatar.png"
      mode="aspectFill"
    />
    <view class="bubble-content" :class="{ self: isSelf, 'has-card': hasCard }">
      <text v-if="msg.type === 'TEXT'" class="text-msg">{{ msg.content }}</text>
      <AppImage v-else-if="msg.type === 'IMAGE'" class="msg-image" :src="msg.content" mode="widthFix" @click="previewImage" />
      <!-- 商品卡片 -->
      <view v-else-if="msg.type === 'PRODUCT'" class="card-msg product-card" @click="goProduct(cardData)">
        <AppImage class="card-cover" :src="cardData.coverImage || cardData.image" mode="aspectFit" fallback="/static/icons/图片.svg" />
        <view class="card-info">
          <text class="card-name text-ellipsis">{{ cardData.name }}</text>
          <text class="card-price">¥{{ Number(cardData.price || 0).toFixed(2) }}</text>
        </view>
        <view class="card-tag">商品</view>
      </view>
      <!-- 订单卡片 -->
      <view v-else-if="msg.type === 'ORDER'" class="card-msg order-card" @click="goOrder(cardData)">
        <view class="card-info" style="flex:1">
          <text class="card-name text-ellipsis">{{ cardData.productName || cardData.orderNo }}</text>
          <view class="card-meta">
            <text class="card-price">¥{{ Number(cardData.amount || 0).toFixed(2) }}</text>
            <text class="card-status">{{ statusText }}</text>
          </view>
          <text class="card-order-no">{{ cardData.orderNo }}</text>
        </view>
        <view class="card-tag">订单</view>
      </view>
      <text v-else class="system-msg">{{ msg.content }}</text>
    </view>
  </view>
</template>

<script setup>
import AppImage from '@/components/AppImage.vue'
import { computed } from 'vue'
import { ORDER_STATUS_TEXT } from '@/utils/constants'

const props = defineProps({
  msg: { type: Object, required: true },
  isSelf: { type: Boolean, default: false },
  avatar: { type: String, default: '' },
  selfAvatar: { type: String, default: '' },
  name: { type: String, default: '' },
  selfName: { type: String, default: '' }
})

function firstChar(str) {
  if (!str || typeof str !== 'string') return '?'
  const s = str.trim()
  return s.length ? s[0] : '?'
}

const cardData = computed(() => {
  if (props.msg.type === 'PRODUCT' || props.msg.type === 'ORDER') {
    try { return JSON.parse(props.msg.content) } catch { return {} }
  }
  return {}
})

const statusText = computed(() => ORDER_STATUS_TEXT[cardData.value?.status] || cardData.value?.status || '')
const hasCard = computed(() => props.msg.type === 'PRODUCT' || props.msg.type === 'ORDER')

function previewImage() {
  uni.previewImage({ urls: [props.msg.content], current: 0 })
}
function goProduct(d) {
  if (d?.id) uni.navigateTo({ url: '/pages/product/detail?id=' + d.id })
}
function goOrder(d) {
  if (d?.id) uni.navigateTo({ url: '/pages/order/detail?id=' + d.id })
}
</script>

<style lang="scss" scoped>
.chat-bubble {
  display: flex;
  align-items: flex-start;
  margin-bottom: 24rpx;
  padding: 0 24rpx;

  &.is-self {
    flex-direction: row-reverse;
  }

  .avatar {
    width: 72rpx;
    height: 72rpx;
    border-radius: 50%;
    flex-shrink: 0;
  }
  .avatar-placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    background: #777777;
    color: #fff;
    font-size: 32rpx;
    font-weight: bold;
  }

  .bubble-content {
    max-width: 70%;
    margin: 0 16rpx;
    padding: 20rpx 24rpx;
    background: #eeeeee;
    border: 1rpx solid #f7f7f7;
    border-radius: 12rpx;
    font-size: 28rpx;
    color: #111111;
    word-break: break-all;

    &.self {
      background: #171717;
      border-color: transparent;
      color: #ffffff;

      /* Some older WebViews do not inherit text colour inside uni-text. */
      .text-msg {
        color: #ffffff !important;
        -webkit-text-fill-color: #ffffff !important;
      }
      .system-msg { color: #ffffff; opacity: 0.82; }

      .card-msg { background: #ffffff !important; border-color: transparent !important; }
      .card-name { color: #111111; }
      .card-price { color: #111111; }
      .card-status { color: #777777; }
      .card-order-no { color: #777777; }
      .card-tag { background: #eeeeee; color: #777777; }
    }
    &.has-card,
    &.has-card.self {
      max-width: 82%;
      padding: 0;
      border: 0;
      background: transparent;
      box-shadow: none;
      &.self { background: transparent !important; }
    }

    .text-msg {
      color: inherit;
      -webkit-text-fill-color: currentColor;
      line-height: 1.55;
    }

    .msg-image {
      max-width: 400rpx;
      border-radius: 8rpx;
    }

    .system-msg {
      color: #777777;
      font-size: 24rpx;
    }
  }
}

/* 卡片消息通用样式 */
.card-msg {
  display: flex;
  padding: 0;
  background: #ffffff;
  border: 0;
  border-radius: 18rpx;
  overflow: hidden;
  position: relative;
  width: 400rpx;
  max-width: 100%;
  min-width: 0;
  box-shadow: 0 2rpx 12rpx rgba(17, 19, 22, 0.05);
}
.card-cover {
  width: 180rpx;
  height: 120rpx;
  flex-shrink: 0;
  margin: 10rpx;
  border-radius: 14rpx;
  overflow: hidden;
}
.card-info {
  flex: 1;
  padding: 16rpx;
  display: flex;
  flex-direction: column;
  justify-content: center;
  overflow: hidden;
}
.card-name {
  font-size: 26rpx;
  color: #111111;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-price {
  font-size: 28rpx;
  color: #111111;
  font-weight: bold;
  margin-top: 8rpx;
}
.card-meta {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 8rpx;
}
.card-status {
  font-size: 22rpx;
  color: #777777;
}
.card-order-no {
  font-size: 20rpx;
  color: #777777;
  margin-top: 4rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-tag {
  position: absolute;
  top: 0;
  right: 0;
  font-size: 18rpx;
  padding: 2rpx 12rpx;
  background: #eeeeee;
  color: #111111;
  border-radius: 0 12rpx 0 8rpx;
}
</style>
