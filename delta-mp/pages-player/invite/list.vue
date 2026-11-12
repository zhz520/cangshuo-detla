<template>
  <view class="invite-list-page">
    <!-- Tab 切换 -->
    <view class="tabs">
      <view class="tab" :class="{active: tab==='received'}" @click="switchTab('received')">收到的邀请</view>
      <view class="tab" :class="{active: tab==='sent'}" @click="switchTab('sent')">发出的邀请</view>
    </view>
    <scroll-view scroll-y class="list" @scrolltolower="loadMore">
      <view v-for="item in records" :key="item.id" class="invite-card">
        <view class="card-top">
          <AppImage :width="160" class="avatar" :src="(tab==='received' ? item.fromAvatar : item.toAvatar) || '/static/images/default-avatar.png'" mode="aspectFill" />
          <view class="info">
            <text class="name">{{ tab === 'received' ? item.fromNickname : item.toNickname }}</text>
            <text class="order-no">订单：{{ item.orderNo || item.orderId }}</text>
            <text class="split-info">分成：{{ SPLIT_TYPE_TEXT[item.splitType] || '平分' }}{{ item.splitType === 'CUSTOM' && item.splitAmount ? ' ¥' + item.splitAmount : '' }}</text>
          </view>
          <view class="status-label" :style="{color: INVITE_STATUS_COLOR[item.status] || '#999'}">{{ INVITE_STATUS_TEXT[item.status] || item.status }}</view>
        </view>
        <view v-if="tab==='received' && item.status==='INVITED'" class="card-actions">
          <button class="btn-accept" :disabled="!!pendingActions[item.orderId]" :loading="pendingActions[item.orderId] === 'accept'" @click="doAccept(item)">接受</button>
          <button class="btn-reject" :disabled="!!pendingActions[item.orderId]" :loading="pendingActions[item.orderId] === 'reject'" @click="doReject(item)">拒绝</button>
        </view>
      </view>
      <PageState :loading="loading && !records.length" :error="error" :empty="!records.length" empty-text="暂无邀请记录" @retry="retry" />
      <view v-if="records.length && loading" class="loading-tip">正在加载…</view>
      <view v-else-if="records.length && finished && !error" class="loading-tip">已显示全部邀请</view>
    </scroll-view>
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import { getInviteList, acceptInvite, rejectInvite } from '@/api/player'
import { usePagedList } from '@/composables/usePagedList'

const INVITE_STATUS_TEXT = { INVITED: '待处理', ACCEPTED: '已接受', REJECTED: '已拒绝', CANCELLED: '已作废' }
const INVITE_STATUS_COLOR = { INVITED: '#996b30', ACCEPTED: '#347a5b', REJECTED: '#b84b55', CANCELLED: '#73777e' }
const SPLIT_TYPE_TEXT = { FIFTY_FIFTY: '五五开', FORTY_SIXTY: '四六开', THIRTY_SEVENTY: '三七开', CUSTOM: '自定义' }
const pendingActions = ref({})
let highlightedOrder = ''
onLoad(options => { highlightedOrder = /^\d{1,19}$/.test(options?.orderId || '') ? options.orderId : '' })
const resolvedActions = new Map()
let disposed = false
const { query: tab, items: records, loading, error, finished, refresh, loadMore, retry, setQuery, cancel, dispose } = usePagedList({
  initialQuery: 'received',
  pageSize: 20,
  async fetchPage({ pageNum, pageSize, query }) {
    const { data } = await getInviteList({ pageNum, pageSize, type: query }, { loading: false, silent: true })
    // The current endpoint returns the full array. Slice it locally; never append it repeatedly.
    const withResolvedStatus = row => row.status === 'INVITED' && resolvedActions.has(String(row.orderId)) ? { ...row, status: resolvedActions.get(String(row.orderId)) } : row
    if (Array.isArray(data)) {
      const matching = query === 'received' && highlightedOrder ? data.find(row => String(row.orderId) === highlightedOrder) : null
      const ordered = matching ? [matching, ...data.filter(row => row !== matching)] : data
      return { records: ordered.slice((pageNum - 1) * pageSize, pageNum * pageSize).map(withResolvedStatus), total: ordered.length }
    }
    if (!Array.isArray(data?.records)) throw new Error('邀请数据异常，请重试')
    return { ...data, records: data.records.map(withResolvedStatus) }
  }
})

onShow(() => refresh())
onHide(cancel)
onUnload(() => { disposed = true; dispose() })

function switchTab(t) { if (t !== tab.value) return setQuery(t) }
/** 接受/拒绝接口需要传 orderId，不能传邀请记录 id */
function getOrderId(item) {
  return item?.orderId ?? null
}
async function doAccept(item) {
  const orderId = getOrderId(item)
  if (orderId == null) { uni.showToast({ title: '数据异常，缺少订单信息', icon: 'none' }); return }
  if (disposed || pendingActions.value[orderId] || resolvedActions.has(String(orderId)) || item.status !== 'INVITED') return
  const splitText = `${SPLIT_TYPE_TEXT[item.splitType] || '平分'}${item.splitType === 'CUSTOM' && item.splitAmount ? ' ¥' + item.splitAmount : ''}`
  const confirmed = await new Promise(resolve => uni.showModal({
    title: '确认接受邀请',
    content: `接受后将按邀请时的分成方案履约：${splitText}。请确认已知悉该分成方案。`,
    confirmText: '确认接受',
    success: r => resolve(r.confirm),
    fail: () => resolve(false)
  }))
  if (!confirmed || disposed) return
  pendingActions.value[orderId] = 'accept'
  try {
    await acceptInvite(orderId)
    if (disposed) return
    resolvedActions.set(String(orderId), 'ACCEPTED')
    records.value = records.value.map(row => String(row.orderId) === String(orderId) ? { ...row, status: 'ACCEPTED' } : row)
    uni.showToast({ title: '已接受' })
    await refresh({ force: true })
  } catch (e) {
    if (!disposed) uni.showToast({ title: e?.msg || e?.data?.msg || e?.message || '接受失败，请重试', icon: 'none' })
  } finally {
    delete pendingActions.value[orderId]
  }
}
async function doReject(item) {
  const orderId = getOrderId(item)
  if (orderId == null) { uni.showToast({ title: '数据异常，缺少订单信息', icon: 'none' }); return }
  if (disposed || pendingActions.value[orderId] || resolvedActions.has(String(orderId)) || item.status !== 'INVITED') return
  pendingActions.value[orderId] = 'reject'
  try {
    const confirmed = await new Promise(resolve => uni.showModal({ title: '提示', content: '确定拒绝该邀请？', success: r => resolve(r.confirm), fail: () => resolve(false) }))
    if (!confirmed || disposed) return
    await rejectInvite(orderId)
    if (disposed) return
    resolvedActions.set(String(orderId), 'REJECTED')
    records.value = records.value.map(row => String(row.orderId) === String(orderId) ? { ...row, status: 'REJECTED' } : row)
    uni.showToast({ title: '已拒绝' })
    await refresh({ force: true })
  } catch (e) {
    if (!disposed) uni.showToast({ title: e?.msg || e?.data?.msg || e?.message || '拒绝失败，请重试', icon: 'none' })
  } finally {
    delete pendingActions.value[orderId]
  }
}
</script>
<style lang="scss" scoped>
.invite-list-page { background: #f7f8fa; min-height: var(--app-page-height, 100vh); }
.tabs { display: flex; background: #fff; border-bottom: 1rpx solid #edf0f2;
  .tab { flex: 1; text-align: center; padding: 24rpx 0; font-size: 28rpx; color: #666b73; position: relative;
    &.active { color: #17191c; &::after { content: ''; position: absolute; bottom: 0; left: 30%; right: 30%; height: 4rpx; background: linear-gradient(135deg, #17191c, #30343a, #30343a); border-radius: 2rpx; } }
  }
}
.list { height: calc(var(--app-page-height, 100vh) - 100rpx); box-sizing: border-box; padding: 20rpx 24rpx calc(24rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); }
.invite-card { background: #fff; border: 1rpx solid #edf0f2; border-radius: 20rpx; padding: 24rpx; margin-bottom: 16rpx;
  .card-top { display: flex; align-items: center; gap: 16rpx; }
  .avatar { width: 72rpx; height: 72rpx; border-radius: 50%; }
  .info { flex: 1; .name { font-size: 28rpx; font-weight: bold; display: block; } .order-no { font-size: 22rpx; color: #73777e; display: block; margin-top: 4rpx; } .split-info { font-size: 22rpx; color: #73777e; display: block; margin-top: 4rpx; } }
  .status-label { font-size: 24rpx; font-weight: 500; white-space: nowrap; }
  .card-actions { display: flex; justify-content: flex-end; gap: 20rpx; margin-top: 16rpx; padding-top: 16rpx; border-top: 1rpx solid #f4f5f6; }
  .btn-accept, .btn-reject { margin: 0; line-height: 1.6; padding: 10rpx 32rpx; font-size: 26rpx; border-radius: 999rpx; &::after { border: 0; } &[disabled] { opacity: .5; } }
  .btn-accept { background: #17191c; color: #fff; font-weight: bold; }
  .btn-reject { background: #fff; border: 1rpx solid #b84b55; color: #b84b55; }
}
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: #73777e; }
</style>
