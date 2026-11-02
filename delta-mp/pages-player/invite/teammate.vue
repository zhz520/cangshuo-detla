<template>
  <view class="invite-page">
    <view class="search-bar">
      <input v-model="keyword" placeholder="搜索接单员昵称" class="search-input" @confirm="searchTeammate" />
      <view class="search-btn" @click="searchTeammate">搜索</view>
    </view>
    <scroll-view scroll-y class="list" @scrolltolower="loadMore">
      <view v-for="p in players" :key="p.id" class="player-card">
        <AppImage :width="160" class="avatar" :src="p.avatar || '/static/images/default-avatar.png'" mode="aspectFill" />
        <view class="info">
          <text class="name">{{ p.nickname }}</text>
          <view class="tags">
            <text v-if="p.avgRating" class="tag">⭐{{ Number(p.avgRating).toFixed(1) }}</text>
            <text class="tag">已完成{{ p.completedOrders || 0 }}单</text>
            <text class="tag" :class="{'tag-warn': p.activeOrders >= maxConcurrent}">进行中{{ p.activeOrders || 0 }}/{{ maxConcurrent }}</text>
          </view>
        </view>
        <button class="invite-btn" :disabled="sending || !!sentIds[p.id]" @click="openInvite(p)">{{ sentIds[p.id] ? '已邀请' : '邀请' }}</button>
      </view>
      <PageState :loading="loading && !players.length" :error="error" :empty="!players.length" empty-text="暂无可邀请的队友" @retry="retry" />
      <view v-if="players.length && loading" class="loading-tip">正在加载…</view>
      <view v-else-if="players.length && finished && !error" class="loading-tip">已显示全部队友</view>
    </scroll-view>
    <!-- 分成方式选择弹窗 -->
    <view v-if="showSplitModal" class="modal-mask" @click="closeInvite">
      <view class="modal-box" @click.stop>
        <text class="modal-title">选择分成方式</text>
        <text class="modal-sub">邀请 {{ inviteTarget?.nickname }}</text>
        <view class="split-options">
          <view v-for="opt in splitOptions" :key="opt.value" class="split-opt" :class="{active: selectedSplit===opt.value}" @click="selectSplit(opt.value)">
            <text class="opt-label">{{ opt.label }}</text>
            <text class="opt-desc">{{ opt.desc }}</text>
          </view>
        </view>
        <view v-if="selectedSplit==='CUSTOM'" class="custom-input-row">
          <text class="custom-label">分成金额 (元)</text>
          <input v-model="customAmount" :disabled="sending" type="digit" placeholder="输入金额" class="custom-input" />
        </view>
        <text v-if="submitError" class="submit-error" role="alert">{{ submitError }}</text>
        <view class="modal-actions">
          <button class="modal-cancel" :disabled="sending" @click="closeInvite">取消</button>
          <button class="modal-confirm" :disabled="sending" :loading="sending" @click="confirmInvite">{{ sending ? '发送中…' : '确认邀请' }}</button>
        </view>
      </view>
    </view>
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import { getAvailableTeammates, inviteTeammate } from '@/api/player'
import { usePagedList } from '@/composables/usePagedList'

const orderId = ref('')
const keyword = ref('')
const maxConcurrent = ref(5)
const sending = ref(false)
const submitError = ref('')
const sentIds = ref({})
let disposed = false
const { query, items: players, loading, error, finished, refresh, loadMore, retry, setQuery, cancel, dispose } = usePagedList({
  pageSize: 20,
  async fetchPage({ pageNum, pageSize, query, isCurrent }) {
    if (!/^\d+$/.test(String(orderId.value))) throw new Error('缺少有效订单编号，请返回订单重新进入')
    const { data } = await getAvailableTeammates({ pageNum, pageSize, orderId: orderId.value, keyword: query }, { loading: false, silent: true })
    if (!Array.isArray(data?.players?.records)) throw new Error('队友数据异常，请重试')
    if (isCurrent() && Number(data.maxConcurrent) > 0) maxConcurrent.value = Number(data.maxConcurrent)
    return data.players
  }
})

// 分成方式相关
const showSplitModal = ref(false)
const inviteTarget = ref(null)
const selectedSplit = ref('FIFTY_FIFTY')
const customAmount = ref('')
const splitOptions = [
  { value: 'FIFTY_FIFTY', label: '五五开', desc: '平分接单员收入' },
  { value: 'FORTY_SIXTY', label: '四六开', desc: '队友40% 你拿60%' },
  { value: 'THIRTY_SEVENTY', label: '三七开', desc: '队友30% 你拿70%' },
  { value: 'CUSTOM', label: '自定义金额', desc: '指定队友分成金额' },
]

onLoad((opts) => { orderId.value = opts?.orderId || '' })
onShow(() => refresh())
onHide(cancel)
onUnload(() => { disposed = true; dispose() })

function searchTeammate() {
  const value = keyword.value.trim()
  return value === query.value ? refresh() : setQuery(value)
}
function closeInvite() { if (!sending.value) showSplitModal.value = false }
function selectSplit(value) { if (!sending.value) { selectedSplit.value = value; submitError.value = '' } }
function openInvite(player) {
  if (disposed || sending.value || sentIds.value[player.id]) return
  inviteTarget.value = player
  selectedSplit.value = 'FIFTY_FIFTY'
  customAmount.value = ''
  submitError.value = ''
  showSplitModal.value = true
}
async function confirmInvite() {
  if (disposed || sending.value || !inviteTarget.value || sentIds.value[inviteTarget.value.id]) return
  const targetId = inviteTarget.value.id
  const data = { inviteePlayerId: inviteTarget.value.id, splitType: selectedSplit.value }
  if (selectedSplit.value === 'CUSTOM') {
    if (!/^\d+(\.\d{1,2})?$/.test(String(customAmount.value)) || !Number.isFinite(Number(customAmount.value)) || Number(customAmount.value) <= 0) {
      return uni.showToast({ title: '请输入有效金额', icon: 'none' })
    }
    data.customAmount = Number(customAmount.value)
  }
  sending.value = true
  submitError.value = ''
  try {
    await inviteTeammate(orderId.value, data)
    if (disposed) return
    sentIds.value[targetId] = true
    uni.showToast({ title: '已发送邀请' })
    showSplitModal.value = false
    await refresh({ force: true })
  } catch (e) {
    if (!disposed) submitError.value = e?.msg || e?.message || '邀请发送失败，请重试'
  } finally {
    sending.value = false
  }
}
</script>
<style lang="scss" scoped>
.invite-page { background: #f7f8fa; min-height: var(--app-page-height, 100vh); }
.search-bar { display: flex; gap: 16rpx; padding: 16rpx 24rpx; background: #fff;
  .search-input { flex: 1; background: #e6e7e9; padding: 16rpx 24rpx; border-radius: 999rpx; font-size: 26rpx; }
  .search-btn { padding: 16rpx 32rpx; background: linear-gradient(135deg, #17191c, #30343a, #30343a); color: #ffffff; font-weight:bold; border-radius: 999rpx; font-size: 26rpx; }
}
.list { height: calc(var(--app-page-height, 100vh) - 120rpx); box-sizing: border-box; padding: 20rpx 24rpx calc(24rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); }
.player-card { display: flex; align-items: center; gap: 16rpx; padding: 24rpx; background: #fff; border: 1rpx solid #edf0f2; border-radius: 20rpx; margin-bottom: 16rpx;
  .avatar { width: 80rpx; height: 80rpx; border-radius: 50%; }
  .info { flex: 1; .name { font-size: 28rpx; font-weight: bold; display: block; } .tags { display: flex; gap: 8rpx; margin-top: 8rpx; flex-wrap: wrap; } .tag { font-size: 22rpx; color: #666b73; background: #e6e7e9; padding: 4rpx 12rpx; border-radius: 4rpx; &.tag-warn { color: #d44955; background: rgba(201, 79, 89, 0.1); } } }
  .invite-btn { margin: 0; line-height: 1.6; padding: 12rpx 24rpx; background: #17191c; color: #fff; font-weight:bold; font-size: 26rpx; border-radius: 999rpx; flex-shrink: 0; &::after { border: 0; } &[disabled] { opacity: .5; } }
}
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: #73777e; }
/* 分成弹窗 */
.modal-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 999; }
.modal-box { width: 85%; max-height: 85vh; overflow-y: auto; box-sizing: border-box; background: #fff; border-radius: 24rpx; padding: 40rpx 32rpx; border: 1rpx solid #e6e7e9; }
.modal-title { font-size: 32rpx; font-weight: bold; display: block; text-align: center; color: #17191c; }
.modal-sub { font-size: 24rpx; color: #73777e; display: block; text-align: center; margin-top: 8rpx; }
.split-options { margin-top: 24rpx; }
.split-opt { display: flex; justify-content: space-between; align-items: center; padding: 20rpx 24rpx; margin-bottom: 12rpx; background: #f4f5f6; border-radius: 12rpx; border: 2rpx solid transparent;
  &.active { border-color: #17191c; background: rgba(23, 25, 28, 0.1); }
  .opt-label { font-size: 28rpx; font-weight: bold; }
  .opt-desc { font-size: 22rpx; color: #73777e; }
}
.custom-input-row { display: flex; align-items: center; gap: 16rpx; margin-top: 16rpx;
  .custom-label { font-size: 26rpx; color: #666b73; white-space: nowrap; }
  .custom-input { flex: 1; background: #e6e7e9; padding: 16rpx 24rpx; border-radius: 12rpx; font-size: 28rpx; }
}
.modal-actions { display: flex; gap: 20rpx; margin-top: 32rpx; }
.modal-actions button { margin: 0; line-height: 1.6; &::after { border: 0; } &[disabled] { opacity: .5; } }
.submit-error { display: block; color: #b84b55; font-size: 24rpx; line-height: 1.6; margin-top: 20rpx; }
.modal-cancel { flex: 1; text-align: center; padding: 20rpx 0; border: 1rpx solid #b9bdc4; border-radius: 999rpx; font-size: 28rpx; color: #666b73; }
.modal-confirm { flex: 1; text-align: center; padding: 20rpx 0; background: linear-gradient(135deg, #17191c, #30343a, #30343a); border-radius: 999rpx; font-size: 28rpx; color: #ffffff; font-weight: bold; }
</style>
