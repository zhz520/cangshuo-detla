<template>
  <view class="account-page">
    <PageState :loading="loading && !accounts.length" :error="error" :empty="loaded && !accounts.length" empty-text="暂无收款账户" @retry="load" />
    <view v-if="loading && accounts.length" class="refresh-tip">正在更新账户…</view>
    <view v-if="actionError" class="action-error" role="alert">{{ actionError }}</view>
    <view v-for="a in accounts" :key="a.id" class="account-card">
      <view class="card-top">
        <AppImage class="type-icon" :src="a.type === 'ALIPAY' ? '/static/icons/钞票.svg' : a.type === 'WECHAT' ? '/static/icons/钞票.svg' : '/static/icons/理财.svg'" mode="aspectFit" />
        <text class="type-name">{{ a.type === 'ALIPAY' ? '支付宝' : a.type === 'WECHAT' ? '微信' : '银行卡' }}</text>
        <view v-if="Number(a.isDefault) === 1" class="default-tag">默认</view>
      </view>
      <text class="account-no">{{ a.accountNo || '账号未记录' }}</text>
      <text v-if="a.accountName" class="real-name">{{ a.accountName }}</text>
      <text v-if="a.identityMatched === false" class="identity-warning">收款人姓名与实名不一致，提现前需修改</text>
      <view class="card-actions">
        <button class="action-btn" :disabled="deletingId !== null" @click="goEdit(a)">编辑</button>
        <button class="action-btn danger" :disabled="deletingId !== null" :loading="deletingId === a.id" @click="doDelete(a.id)">删除</button>
      </view>
    </view>
    <button class="add-btn" :disabled="deletingId !== null" @click="goAdd">+ 添加账户</button>
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import { getAccountList, deleteAccount } from '@/api/player'

const accounts = ref([])
const loading = ref(true)
const loaded = ref(false)
const error = ref('')
const actionError = ref('')
const deletingId = ref(null)
let active, pendingRead, version = 0, disposed = false

onShow(() => load())
onHide(() => { version++; active = null; loading.value = false })
onUnload(() => { disposed = true; version++ })
function load(force = false) {
  if (disposed) return Promise.resolve()
  if (deletingId.value !== null && force !== true) return Promise.resolve()
  if (active && force !== true) return active
  const current = ++version
  const preceding = pendingRead
  loading.value = true
  error.value = ''
  const task = Promise.resolve().then(async () => {
    try {
      if (preceding) await preceding
      if (disposed || current !== version) return
      const { data } = await getAccountList({ loading: false, silent: true })
      if (!Array.isArray(data)) throw new Error('收款账户数据异常，请重试')
      if (!disposed && current === version) { accounts.value = data; loaded.value = true }
    } catch (e) {
      if (!disposed && current === version) error.value = e?.msg || e?.message || '账户加载失败，请重试'
    } finally {
      if (pendingRead === task) pendingRead = null
      if (!disposed && current === version) { loading.value = false; active = null }
    }
  })
  active = task
  pendingRead = task
  return task
}

function goAdd() { if (!disposed && deletingId.value === null) uni.navigateTo({ url: '/pages-player/account/edit' }) }
function goEdit(a) { if (!disposed && deletingId.value === null) uni.navigateTo({ url: '/pages-player/account/edit?id=' + encodeURIComponent(a.id) }) }
async function doDelete(id) {
  if (disposed || id == null || deletingId.value !== null) return
  deletingId.value = id
  actionError.value = ''
  try {
    const confirmed = await new Promise(resolve => uni.showModal({ title: '提示', content: '确认删除该账户吗', success: r => resolve(r.confirm), fail: () => resolve(false) }))
    if (!confirmed || disposed) return
    await deleteAccount(id)
    if (disposed) return
    // Invalidate a read started before deletion, so it cannot resurrect the deleted account.
    const previousRead = pendingRead
    version++
    active = null
    loading.value = false
    accounts.value = accounts.value.filter(a => String(a.id) !== String(id))
    loaded.value = true
    uni.showToast({ title: '已删除' })
    // GETs are coalesced by the request layer. Let the old read settle before the fresh read.
    if (previousRead) await previousRead
    if (disposed) return
    await load(true)
  } catch (e) {
    if (!disposed) actionError.value = e?.msg || e?.message || '删除失败，请点击删除重试'
  } finally {
    deletingId.value = null
  }
}
</script>
<style lang="scss" scoped>
.account-page { background: #f7f8fa; min-height: var(--app-page-height, 100vh); box-sizing: border-box; padding: 24rpx 24rpx calc(32rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); }
.refresh-tip { color: #777777; font-size: 24rpx; text-align: center; padding: 16rpx; }
.action-error { color: #b84b55; background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; font-size: 25rpx; }
.account-card { background: #fff; border: 1rpx solid #edf0f2; border-radius: 20rpx; padding: 24rpx; margin-bottom: 20rpx;
  .card-top { display: flex; align-items: center; gap: 8rpx; margin-bottom: 12rpx;
    .type-icon { width: 36rpx; height: 36rpx; } .type-name { font-size: 28rpx; font-weight: bold; }
    .default-tag { font-size: 20rpx; color: #111111; background: #f7f7f8; padding: 2rpx 12rpx; border-radius: 4rpx; margin-left: auto; }
  }
  .account-no { font-size: 32rpx; color: #111111; letter-spacing: 2rpx; display: block; word-break: break-all; }
  .real-name { font-size: 24rpx; color: #777777; display: block; margin-top: 4rpx; }
  .identity-warning { font-size: 22rpx; line-height: 1.5; color: #a04b55; display: block; margin-top: 6rpx; }
  .card-actions { display: flex; justify-content: flex-end; gap: 24rpx; margin-top: 16rpx; padding-top: 16rpx; border-top: 1rpx solid #f7f7f7;
    .action-btn { margin: 0; padding: 8rpx 16rpx; background: #f7f8fa; font-size: 26rpx; line-height: 1.6; color: #111111; border-radius: 12rpx; &::after { border: 0; } &[disabled] { opacity: .5; } &.danger { color: #b84b55; } }
  }
}
.add-btn { text-align: center; line-height: 1.6; padding: 24rpx; background: #111111; border-radius: 999rpx; font-size: 28rpx; color: #fff; margin-top: 20rpx; &::after { border: 0; } &[disabled] { opacity: .5; } }
</style>
