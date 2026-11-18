<template>
  <view class="lookup-page">
    <view class="lookup-card">
      <text class="title">游客订单查询</text>
      <text class="subtitle">使用下单时填写的手机号和 6 至 12 位查单码进入订单。</text>
      <view class="field">
        <text class="label">手机号</text>
        <input v-model="phone" type="number" maxlength="11" placeholder="请输入下单手机号" />
      </view>
      <view class="field">
        <text class="label">查单码</text>
        <input v-model="lookupCode" :password="true" maxlength="12" placeholder="请输入 6 至 12 位数字或字母" />
      </view>
      <view class="button" :class="{ disabled: submitting }" @click="submit">
        {{ submitting ? '处理中…' : (userStore.isLoggedIn ? '绑定到当前账号' : '查询游客订单') }}
      </view>
      <text v-if="userStore.isLoggedIn" class="hint">绑定后，已完结的游客订单会出现在当前账号的订单列表中。游客订单 ID 不会被改写。</text>
      <text v-else class="hint">查单码由游客下单时自行填写，平台无法查看明文。如忘记，请联系客服核实订单信息。</text>
      <view v-if="userStore.isLoggedIn" class="login-link" @click="openLogin">切换正式账号</view>
      <view v-else class="login-link" @click="openLogin">已有正式账号，去登录</view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { lookupGuestOrder, bindGuestOrder } from '@/api/guest'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const phone = ref('')
const lookupCode = ref('')
const submitting = ref(false)

function valid() {
  if (!/^1[0-9]{10}$/.test(phone.value)) {
    uni.showToast({ title: '请输入正确的手机号', icon: 'none' })
    return false
  }
  if (!/^[A-Za-z0-9]{6,12}$/.test(lookupCode.value.trim())) {
    uni.showToast({ title: '查单码须为6至12位数字或字母', icon: 'none' })
    return false
  }
  return true
}

async function submit() {
  if (submitting.value || !valid()) return
  submitting.value = true
  try {
    if (userStore.isLoggedIn) {
      await bindGuestOrder({ phone: phone.value, lookupCode: lookupCode.value.trim() })
      uni.showToast({ title: '绑定成功', icon: 'success' })
      setTimeout(() => uni.redirectTo({ url: '/pages/order/list' }), 600)
    } else {
      const { data } = await lookupGuestOrder({ phone: phone.value, lookupCode: lookupCode.value.trim() })
      userStore.setGuestSession(data)
      uni.showToast({ title: '查单成功', icon: 'success' })
      setTimeout(() => uni.redirectTo({ url: '/pages/order/list' }), 400)
    }
  } catch (e) {
    uni.showToast({ title: e?.msg || e?.message || '查单失败，请核对信息', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function openLogin() {
  if (userStore.isLoggedIn) {
    userStore.logout()
  } else {
    uni.navigateTo({ url: '/pages/login/index' })
  }
}
</script>

<style lang="scss" scoped>
.lookup-page { min-height: var(--app-page-height, 100vh); padding: 28rpx; background: var(--store-soft); box-sizing: border-box; }
.lookup-card { padding: 34rpx 28rpx; background: var(--store-surface); border: 1rpx solid var(--store-line); border-radius: 20rpx; box-shadow: 0 12rpx 36rpx rgba(20, 28, 40, .05); }
.title { display: block; color: var(--store-text); font-size: 38rpx; font-weight: 700; }
.subtitle, .hint { display: block; color: var(--store-regular); font-size: 25rpx; line-height: 1.7; }
.subtitle { margin-top: 12rpx; }
.field { margin-top: 28rpx; }
.label { display: block; margin-bottom: 12rpx; color: var(--store-text); font-size: 27rpx; font-weight: 600; }
input { height: 84rpx; padding: 0 24rpx; box-sizing: border-box; border: 1rpx solid var(--store-line); border-radius: 14rpx; background: var(--store-soft); color: var(--store-text); font-size: 28rpx; }
.button { height: 88rpx; margin-top: 32rpx; border-radius: 999rpx; background: var(--store-primary); color: #fff; font-size: 30rpx; font-weight: 700; line-height: 88rpx; text-align: center; }
.button.disabled { opacity: .55; }
.hint { margin-top: 22rpx; }
.login-link { margin-top: 28rpx; color: var(--store-regular); font-size: 26rpx; text-align: center; }
</style>
