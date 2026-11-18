<template>
  <view class="detail">
    <PageState v-if="!order" :loading="loading" :error="error" :empty="!loading && !error" empty-text="暂无订单信息" @retry="loadDetail" />
    <template v-if="order">
    <PageState v-if="error" :error="error" @retry="loadDetail" />
    <GrabAcceptDialog ref="grabDialog" @accepted="refreshAfterAccept" />
    <!-- 状态栏 -->
    <view class="status-bar">
      <view class="status-left"><StatusTag :status="order.status" /></view>
      <view class="status-right">
        <text class="order-no">{{ order.orderNo }}</text>
        <text class="copy-link" @click="copyOrderNo">复制单号</text>
      </view>
    </view>
    <!-- 订单信息 -->
    <view class="card">
      <view class="card-header"><view class="title-bar" /><text class="card-title">订单信息</text></view>
      <view class="info-row"><text class="label">商品</text><text class="value bold">{{ order.productName }}</text></view>
      <view v-if="order.specCombination || order.specInfo" class="info-row"><text class="label">规格</text><text class="value">{{ order.specCombination || order.specInfo }}</text></view>
      <view class="info-row"><text class="label">订单金额</text><PriceText :value="order.amount" :size="32" /></view>
      <view class="divider" />
      <view class="income-block">
        <view class="income-main">
          <text class="income-label">到手金额</text>
          <text class="income-val">¥{{ incomeAmount(order).toFixed(2) }}</text>
        </view>
        <text class="income-hint">抽佣比例 {{ commissionPercent(order) }}%</text>
      </view>
    </view>
    <!-- 用户信息 -->
    <view class="card" v-if="order.userNickname">
      <view class="card-header"><view class="title-bar" /><text class="card-title">用户信息</text></view>
      <view class="user-row">
        <AppImage :width="160" class="u-avatar" :src="order.userAvatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
        <text class="u-name">{{ order.userNickname }}</text>
      </view>
    </view>
    <!-- 动态字段 -->
    <view class="card" v-if="parsedExtra.length">
      <view class="card-header"><view class="title-bar" /><text class="card-title">订单详情</text></view>
      <view v-for="item in parsedExtra" :key="item.key" class="info-row info-row-copy">
        <text class="label">{{ item.key }}</text>
        <text class="value">{{ item.value }}</text>
        <text class="copy-link row-copy-link" @click="copyDetailItem(item)">复制</text>
      </view>
    </view>
    <!-- 队友信息 -->
    <view class="card" v-if="order.teammates && order.teammates.length">
      <view class="card-header"><view class="title-bar" /><text class="card-title">队友信息</text></view>
      <view v-for="t in order.teammates" :key="t.id" class="teammate-row">
        <AppImage :width="160" class="t-avatar" :src="t.avatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
        <text class="t-name">{{ t.nickname }}</text>
        <StatusTag :status="t.status" :textMap="teammateStatusText" :colorMap="teammateStatusColor" />
        <view v-if="canInvite && canReplace(t)" class="team-controls">
          <button v-if="!replacementFor(t)" class="teammate-replace text" :disabled="actionDisabled" @click="replaceActiveTeammate(t)">更换</button>
          <text v-else class="replace-pending">等待新队友接受</text>
          <button class="teammate-replace text" :disabled="actionDisabled" @click="openTeamRemove(t)">立即移除</button>
        </view>
        <button v-if="canInvite && t.status === 'INVITED'" class="teammate-replace text" :disabled="actionDisabled" @click="revokeTeamInvite(t)">撤回</button>
        <text v-if="t.status === 'INVITED' && t.replacesParticipantId" class="replace-pending">接替邀请</text>
      </view>
    </view>
    <!-- 进度历史 -->
    <view class="card" v-if="progressList.length || progressLoading || progressError">
      <view class="card-header"><view class="title-bar" /><text class="card-title">进度历史</text></view>
      <PageState :loading="progressLoading && !progressList.length" :error="progressError" @retry="loadProgress" />
      <view v-for="(p, idx) in progressList" :key="idx" class="progress-item">
        <text class="p-content">{{ p.content }}</text>
        <text class="p-time">{{ p.createdAt }}</text>
        <view v-if="p.images" class="p-images">
          <AppImage v-for="(img,i) in p.images.split(',')" :key="i" :src="img" mode="aspectFill" class="p-img" @click="previewImg(p.images.split(','),i)" />
        </view>
      </view>
    </view>
    <!-- 操作按钮 -->
    <PageState v-if="actionError" :error="actionError" @retry="retryAction" />
    <view class="actions" :class="{ 'actions-disabled': actionDisabled }" :aria-busy="!!actionBusy">
      <view v-if="['PAID','ASSIGNED'].includes(order.status)" class="btn" @click="doAccept">接单</view>
      <view v-if="order.status==='ASSIGNED'" class="btn-warn" @click="doReject">拒绝</view>
      <view v-if="['ACCEPTED','WAITING_TEAMMATE'].includes(order.status)" class="btn" @click="doStart">开始服务</view>
      <view v-if="order.status==='IN_PROGRESS'" class="btn" @click="goProgress">提交进度</view>
      <view v-if="order.status==='IN_PROGRESS'" class="btn btn-success" @click="doComplete">完成服务</view>
      <view v-if="isPrimary && order.status==='IN_PROGRESS'" class="btn" @click="openReplaceModal">退出订单</view>
      <view v-if="canInvite && !teamFull" class="btn" @click="goInvite">邀请队友</view>
      <view v-if="['ACCEPTED','WAITING_TEAMMATE','IN_PROGRESS','COMPLETED','CONFIRMED'].includes(order.status)" class="btn-ghost" @click="goChat">聊天</view>
    </view>
    <view v-if="removeTarget" class="modal-mask" @click="closeTeamRemove">
      <view class="modal-box" @click.stop>
        <text class="modal-title">立即移除队友</text>
        <text class="modal-sub">确认后 {{ removeTarget.nickname || '原队友' }} 退出本单及分成。</text>
        <textarea v-model="removeReason" maxlength="200" class="remove-reason" placeholder="填写移除原因" :disabled="!!actionBusy" />
        <PageState v-if="actionError" :error="actionError" @retry="submitTeamRemove" />
        <view class="modal-actions">
          <view class="modal-cancel" @click="closeTeamRemove">取消</view>
          <view class="btn" @click="submitTeamRemove">确认移除</view>
        </view>
      </view>
    </view>
    <!-- 完成服务弹窗 -->
    <view v-if="showCompleteModal" class="modal-mask" @click="closeCompleteModal">
      <view class="modal-box" @click.stop>
        <text class="modal-title">完成服务</text>
        <text class="modal-sub">系统会自动新增一条名为“结束服务”的进度，必须上传图片后才能完成。</text>
        <view class="complete-block">
          <text class="complete-label">结束服务图片</text>
          <view :class="{ 'actions-disabled': !!actionBusy }"><ImageUploader v-model="endServiceImages" :max="6" /></view>
        </view>
        <PageState v-if="actionError" :error="actionError" @retry="retryAction" />
        <view class="modal-actions">
          <view class="modal-cancel" @click="closeCompleteModal">取消</view>
          <view class="btn btn-success complete-submit" :class="{ 'actions-disabled': !!actionBusy }" @click="submitComplete">{{ actionBusy === 'complete' ? '提交中...' : '确认完成' }}</view>
        </view>
      </view>
    </view>
    <!-- 换人弹窗 -->
    <view v-if="showReplaceModal" class="modal-mask" @click="closeReplaceModal">
      <view class="modal-box" @click.stop>
        <text class="modal-title">退出订单</text>
        <view class="replace-options" :class="{ 'actions-disabled': !!actionBusy }">
          <view v-if="hasTeammate" class="replace-opt" @click="doReplace('all')">
            <text class="opt-label">全队退出</text>
            <text class="opt-desc">主接单员和队友都退出，订单回到接单大厅</text>
          </view>
          <view v-if="!hasTeammate" class="replace-opt" @click="doReplace('self')">
            <text class="opt-label">换自己</text>
            <text class="opt-desc">自己退出不拿钱，订单回到接单大厅</text>
          </view>
        </view>
        <PageState v-if="actionError" :error="actionError" @retry="retryAction" />
        <view class="modal-actions">
          <view class="modal-cancel" @click="closeReplaceModal">取消</view>
        </view>
      </view>
    </view>
    </template>
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref, computed } from 'vue'
import GrabAcceptDialog from '@/components/GrabAcceptDialog.vue'
const grabDialog=ref(null)
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import StatusTag from '@/components/StatusTag.vue'
import PriceText from '@/components/PriceText.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import PageState from '@/components/PageState.vue'
import { startOrder, completeOrder, getPlayerOrderProgress, rejectAssign, replaceSelf, replaceAll, revokeOrderTeammate, removeOrderTeammate } from '@/api/player'
import { getPlayerOrderDetail as getOrderDetail } from '@/api/player'
import { requestPlayerSubscribe } from '@/utils/subscribe'
import { usePlayerStore } from '@/store/player'

const order = ref(null)
const orderId = ref(0)
const playerStore = usePlayerStore()
const progressList = ref([])
const loading = ref(true)
const error = ref('')
const progressLoading = ref(false)
const progressError = ref('')
const actionBusy = ref('')
const actionError = ref('')
const leaving = ref(false)
const actionDisabled = computed(() => loading.value || !!error.value || !!actionBusy.value || leaving.value)
let disposed = false, detailVersion = 0, progressVersion = 0
let detailTask = null, lastDetailTask = null, progressTask = null, lastProgressTask = null
let failedAction = null, backTimer = null
const parsedExtra = computed(() => {
  const o = order.value
  if (!o) return []
  // 优先展示新版动态字段
  if (o.extraFields) {
    try {
      const obj = typeof o.extraFields === 'string' ? JSON.parse(o.extraFields) : o.extraFields
      return Object.entries(obj).map(([key, value]) => ({ key, value }))
    } catch { /* fall through */ }
  }
  // 兼容老订单：回退展示 gameAccount/contact/remark
  const legacy = []
  if (o.gameAccount) legacy.push({ key: '关联账号', value: o.gameAccount })
  if (o.contact) legacy.push({ key: '联系ID', value: o.contact })
  if (o.remark) legacy.push({ key: '备注', value: o.remark })
  return legacy
})
const showReplaceModal = ref(false)
const showCompleteModal = ref(false)
const endServiceImages = ref([])
const isPrimary = computed(() => Boolean(order.value?.playerId) && Number(order.value.playerId) === Number(playerStore.playerId))
const canInvite = computed(() => isPrimary.value && ['ACCEPTED', 'WAITING_TEAMMATE', 'IN_PROGRESS'].includes(order.value?.status))
const canReplace = teammate => teammate?.playerId && teammate.status === 'ACCEPTED'
const team = computed(() => order.value?.teammates || [])
const hasTeammate = computed(() => team.value.some(t => ['ACCEPTED', 'INVITED'].includes(t.status)))
const replacementFor = teammate => team.value.find(row => row.status === 'INVITED' && String(row.replacesParticipantId) === String(teammate.id))
const teamFull = computed(() => team.value.filter(t => t.status === 'ACCEPTED' || (t.status === 'INVITED' && !t.replacesParticipantId)).length
  >= Math.max(1, (Number(order.value?.requiredPlayerCount) || 1) - 1))
const removeTarget = ref(null), removeReason = ref('')

const teammateStatusText = { INVITED: '待处理', ACCEPTED: '已接受', REJECTED: '已拒绝', CANCELLED: '已作废', REPLACED: '已退出', EXPIRED: '已过期' }
const teammateStatusColor = { INVITED: '#e6a23c', ACCEPTED: '#07c160', REJECTED: '#ee0a24', CANCELLED: '#999', REPLACED: '#909399' }

onLoad((opts) => { orderId.value = opts?.id || 0 })
onShow(() => {
  unlockH5TabbarPageScroll()
  if (!playerStore.playerInfo) playerStore.fetchProfile()
  loadDetail()
})
onHide(() => {
  detailVersion++; progressVersion++
  detailTask = null; progressTask = null
  loading.value = false; progressLoading.value = false
})
onUnload(() => {
  disposed = true; detailVersion++; progressVersion++
  clearTimeout(backTimer)
})

function unlockH5TabbarPageScroll() {
  // #ifdef H5
  document.documentElement.classList.remove('tabbar-page-locked')
  document.body.classList.remove('tabbar-page-locked')
  // #endif
}

/** 订单抽佣比例（0~1），仅用订单字段，无则按 0 */
function commissionRateForOrder(o) {
  if (o && (o.commissionRate === 0 || (o.commissionRate != null && o.commissionRate !== ''))) {
    return Number(o.commissionRate)
  }
  return 0
}
function commissionPercent(o) {
  return Math.round(commissionRateForOrder(o) * 100)
}
function incomeAmount(o) {
  const n = Number(o?.amount) || 0
  return n * (1 - commissionRateForOrder(o))
}

function messageFor(err, fallback) { return String(err?.msg || err?.data?.msg || err?.message || fallback) }
function loadDetail({ force = false } = {}) {
  if (disposed) return Promise.resolve(false)
  if (detailTask && !force) return detailTask
  const previous = lastDetailTask, run = ++detailVersion, id = orderId.value
  const current = () => !disposed && run === detailVersion
  loading.value = true; error.value = ''
  const task = Promise.resolve().then(async () => {
    try {
      if (previous) await previous
      if (!current()) return false
      if (!id) throw new Error('缺少订单信息，请返回列表重试')
      const res = await getOrderDetail(id)
      if (!current()) return false
      if (!res.data || typeof res.data !== 'object') throw new Error('订单不存在或已不可查看')
      order.value = res.data
      await loadProgress({ force })
      return true
    } catch (err) {
      if (current()) error.value = messageFor(err, '订单加载失败，请重试')
      return false
    } finally {
      if (current()) { loading.value = false; detailTask = null }
    }
  })
  detailTask = lastDetailTask = task
  return task
}
function loadProgress({ force = false } = {}) {
  if (disposed) return Promise.resolve(false)
  if (progressTask && !force) return progressTask
  const previous = lastProgressTask, run = ++progressVersion, id = orderId.value
  const current = () => !disposed && run === progressVersion
  progressLoading.value = true; progressError.value = ''
  const task = Promise.resolve().then(async () => {
    try {
      if (previous) await previous
      if (!current()) return false
      const res = await getPlayerOrderProgress(id)
      if (!current()) return false
      if (!Array.isArray(res.data)) throw new Error('进度数据异常，请重试')
      progressList.value = res.data
      return true
    } catch (err) {
      if (current()) progressError.value = messageFor(err, '进度加载失败，请重试')
      return false
    } finally {
      if (current()) { progressLoading.value = false; progressTask = null }
    }
  })
  progressTask = lastProgressTask = task
  return task
}
function refreshAfterAccept() { return loadDetail({ force: true }) }

async function runAction(name, action, retry) {
  if (disposed || actionDisabled.value) return false
  actionBusy.value = name; actionError.value = ''; failedAction = null
  try { await action(); return !disposed }
  catch (err) {
    if (!disposed) { actionError.value = messageFor(err, '操作失败，请重试'); failedAction = retry }
    return false
  } finally { if (!disposed) actionBusy.value = '' }
}
function retryAction() { return failedAction?.() }
function confirmAction(options) {
  return new Promise(resolve => uni.showModal({ ...options, success: result => resolve(!!result.confirm), fail: () => resolve(false) }))
}
function leaveWithToast(title, delay = 0) {
  if (disposed) return
  leaving.value = true
  uni.showToast({ title })
  if (delay) backTimer = setTimeout(() => { if (!disposed) uni.navigateBack() }, delay)
  else uni.navigateBack()
}
function doAccept() {
  return runAction('accept', async () => {
    await requestPlayerSubscribe()
    if (!disposed) await grabDialog.value?.open(orderId.value)
  }, doAccept)
}
function doReject() {
  return runAction('reject', async () => {
    if (!await confirmAction({ title: '提示', content: '确定拒绝该指派？' }) || disposed) return
    await rejectAssign(orderId.value)
    leaveWithToast('已拒绝')
  }, doReject)
}
function doStart() {
  return runAction('start', async () => {
    await startOrder(orderId.value)
    if (disposed) return
    uni.showToast({ title: '已开始' })
    await loadDetail({ force: true })
  }, doStart)
}
function doComplete() { if (!actionDisabled.value) { actionError.value = ''; showCompleteModal.value = true } }
function closeCompleteModal() {
  if (actionBusy.value) return
  showCompleteModal.value = false
  endServiceImages.value = []
}
function submitComplete() {
  if (disposed || actionDisabled.value) return Promise.resolve(false)
  if (!endServiceImages.value.length) {
    return uni.showToast({ title: '请至少上传一张结束服务图片', icon: 'none' })
  }
  const images = endServiceImages.value.join(',')
  return runAction('complete', async () => {
    await requestPlayerSubscribe()
    if (disposed) return
    await completeOrder(orderId.value, { images })
    if (disposed) return
    uni.showToast({ title: '已完成' })
    showCompleteModal.value = false; endServiceImages.value = []
    await loadDetail({ force: true })
  }, submitComplete)
}
function goProgress() { if (!actionDisabled.value) uni.navigateTo({ url: '/pages-player/order/progress?orderId=' + orderId.value }) }
function goChat() { if (!actionDisabled.value) uni.navigateTo({ url: '/pages-player/chat/room?orderId=' + orderId.value }) }
function goInvite() { if (!actionDisabled.value) uni.navigateTo({ url: '/pages-player/invite/teammate?orderId=' + orderId.value }) }
function previewImg(imgs, idx) { uni.previewImage({ urls: imgs, current: idx }) }
function copyText(text, title) {
  if (!text) {
    return uni.showToast({ title: '暂无可复制内容', icon: 'none' })
  }
  uni.setClipboardData({
    data: text,
    success: () => uni.showToast({ title, icon: 'none' })
  })
}
function copyOrderNo() {
  copyText(order.value?.orderNo || '', '订单号已复制')
}
function copyDetailItem(item) {
  copyText(String(item?.value ?? ''), `${item?.key || '内容'}已复制`)
}
function openReplaceModal() { if (!actionDisabled.value) { actionError.value = ''; showReplaceModal.value = true } }
function closeReplaceModal() { if (!actionBusy.value) showReplaceModal.value = false }
function replaceActiveTeammate(teammate) {
  if (!canInvite.value || !canReplace(teammate) || replacementFor(teammate) || actionDisabled.value) return
  const teammateId = encodeURIComponent(String(teammate.playerId))
  uni.navigateTo({ url: '/pages-player/invite/teammate?orderId=' + orderId.value + '&replacedTeammateId=' + teammateId })
}
function openTeamRemove(teammate) {
  if (!canInvite.value || !canReplace(teammate) || actionDisabled.value) return
  removeTarget.value = teammate; removeReason.value = ''; actionError.value = ''
}
function closeTeamRemove() {
  if (!actionBusy.value) { removeTarget.value = null; actionError.value = ''; failedAction = null }
}
function submitTeamRemove() {
  if (!canInvite.value || !removeTarget.value?.id || actionDisabled.value) return
  const reason = removeReason.value.trim()
  if (!reason) { actionError.value = '请填写移除原因'; return }
  const id = removeTarget.value.id
  return runAction('remove-teammate', async () => {
    await removeOrderTeammate(orderId.value, id, reason)
    if (disposed) return
    removeTarget.value = null
    uni.showToast({ title: '队友已移除', icon: 'none' })
    await loadDetail({ force: true })
  }, submitTeamRemove)
}
function revokeTeamInvite(teammate) {
  if (!canInvite.value || teammate?.status !== 'INVITED' || !teammate.id || actionDisabled.value) return
  return runAction('revoke-teammate', async () => {
    if (!await confirmAction({ title: '撤回邀请', content: '确定撤回给 ' + (teammate.nickname || '该队友') + ' 的邀请？' }) || disposed) return
    await revokeOrderTeammate(orderId.value, teammate.id)
    if (disposed) return
    uni.showToast({ title: '邀请已撤回', icon: 'none' })
    await loadDetail({ force: true })
  }, () => revokeTeamInvite(teammate))
}
function doReplace(type) {
  const msgs = {
    all: '确定两个都换？主接单员和队友都将退出，订单回到接单大厅。',
    self: '确定换自己？你将退出该订单且不参与分成，订单回到接单大厅。',
  }
  if (!msgs[type]) return Promise.resolve(false)
  return runAction('replace', async () => {
    if (!await confirmAction({ title: '确认换人', content: msgs[type] }) || disposed) return
    if (type === 'all') await replaceAll(orderId.value)
    else if (type === 'self') await replaceSelf(orderId.value)
    if (disposed) return
    showReplaceModal.value = false
    leaveWithToast('已退出订单', 1500)
  }, () => doReplace(type))
}
</script>
<style lang="scss" scoped>
.detail { background: var(--store-soft); min-height: var(--app-page-height, 100vh); padding-bottom: 40rpx; }
.status-bar {
  display: flex; justify-content: space-between; align-items: center;
  padding: 28rpx 32rpx; background: var(--store-surface);
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,0.04);
  .status-right { display: flex; align-items: center; gap: 16rpx; }
  .order-no { font-size: 24rpx; color: var(--store-regular); }
}
.card {
  margin: 20rpx 24rpx 0; padding: 28rpx 28rpx 24rpx;
  background: var(--store-surface); border-radius: 16rpx;
  box-shadow: 0 2rpx 16rpx rgba(0,0,0,0.04);
}
.card-header {
  display: flex; align-items: center; margin-bottom: 20rpx;
}
.title-bar {
  width: 6rpx; height: 28rpx; border-radius: 3rpx;
  background: var(--store-primary);
  margin-right: 12rpx;
}
.card-title { font-size: 28rpx; font-weight: bold; color: var(--store-text); }
.copy-link {
  padding: 8rpx 18rpx;
  font-size: 22rpx;
  color: var(--store-text);
  border: 1rpx solid rgba(23, 25, 28, 0.28);
  border-radius: 999rpx;
  flex-shrink: 0;
}
.row-copy-link {
  padding: 6rpx 16rpx;
  font-size: 20rpx;
}
.info-row {
  display: flex; align-items: center; padding: 12rpx 0;
  .label { font-size: 26rpx; color: var(--store-regular); width: 140rpx; flex-shrink: 0; }
  .value { font-size: 26rpx; color: var(--store-text); flex: 1; &.bold { font-weight: 600; } }
}
.info-row-copy .value { min-width: 0; margin-right: 12rpx; }
.divider { height: 1rpx; background: var(--store-soft); margin: 16rpx 0; }
.income-block {
  background: linear-gradient(135deg, rgba(23, 25, 28, 0.06), rgba(23, 25, 28, 0.02));
  border-radius: 12rpx; padding: 20rpx 24rpx;
}
.income-main { display: flex; align-items: baseline; gap: 12rpx; }
.income-label { font-size: 26rpx; color: var(--store-regular); }
.income-val { font-size: 36rpx; color: #d44955; font-weight: bold; }
.income-hint { font-size: 22rpx; color: var(--store-regular); margin-top: 6rpx; display: block; }
.teammate-row { display: flex; align-items: center; gap: 12rpx; padding: 16rpx 0; border-bottom: 1rpx solid var(--store-line); .t-avatar { width: 60rpx; height: 60rpx; border-radius: 50%; } .t-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 26rpx; color: var(--store-text); } }
.teammate-replace { flex-shrink: 0; margin: 0; padding: 0 14rpx; background: transparent; color: var(--app-text, var(--store-text)); font-size: 24rpx; line-height: 68rpx; }
.teammate-replace::after { border: 0; }
.team-controls { display: flex; align-items: center; flex-wrap: wrap; }
.replace-pending { color: var(--app-text-secondary, #777); font-size: 22rpx; }
.teammate-row { flex-wrap: wrap; }
.remove-reason { width: 100%; min-height: 132rpx; box-sizing: border-box; margin-top: 20rpx; padding: 16rpx; border: 1rpx solid #ccc; border-radius: 12rpx; font-size: 28rpx; }
.progress-item {
  padding: 16rpx 0; border-bottom: 1rpx solid var(--store-line);
  .p-content { font-size: 26rpx; color: var(--store-text); display: block; }
  .p-time { font-size: 22rpx; color: var(--store-regular); display: block; margin-top: 6rpx; }
  .p-images { display: flex; gap: 12rpx; margin-top: 12rpx; }
  .p-img { width: 120rpx; height: 120rpx; border-radius: 12rpx; }
}
.actions { display: flex; gap: 20rpx; padding: 40rpx 24rpx; flex-wrap: wrap; justify-content: flex-end; }
.btn { padding: 16rpx 40rpx; background: var(--store-primary); color: #ffffff; font-weight:bold; font-size: 28rpx; border-radius: 999rpx; &.btn-success { background: #2d7a56; } }
.btn-ghost { padding: 16rpx 40rpx; border: 1rpx solid var(--store-line); color: var(--store-text); font-size: 28rpx; border-radius: 999rpx; }
.btn-warn { padding: 16rpx 40rpx; border: 1rpx solid rgba(201, 79, 89, 0.4); color: #d44955; font-size: 28rpx; border-radius: 999rpx; }
.user-row { display: flex; align-items: center; gap: 16rpx; .u-avatar { width: 72rpx; height: 72rpx; border-radius: 50%; border: 2rpx solid #f7f7f7; } .u-name { font-size: 28rpx; color: var(--store-text); font-weight: 500; } }
/* 换人弹窗 */
.modal-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 999; }
.modal-box { width: 85%; background: var(--store-surface); border-radius: 16rpx; padding: 40rpx 32rpx; border: 1rpx solid var(--store-line); }
.modal-title { font-size: 32rpx; font-weight: bold; display: block; text-align: center; color: var(--store-text); }
.modal-sub { font-size: 24rpx; color: var(--store-regular); display: block; text-align: center; margin-top: 8rpx; }
.complete-block { margin-top: 24rpx; }
.complete-label { font-size: 26rpx; color: var(--store-text); display: block; margin-bottom: 16rpx; }
.replace-options { margin-top: 24rpx; }
.replace-opt { display: flex; flex-direction: column; padding: 24rpx; margin-bottom: 16rpx; background: var(--store-soft); border-radius: 12rpx;
  &:active { background: rgba(23, 25, 28, 0.1); }
  .opt-label { font-size: 28rpx; font-weight: bold; color: var(--store-text); }
  .opt-desc { font-size: 22rpx; color: var(--store-regular); margin-top: 6rpx; }
}
.modal-actions { display: flex; gap: 20rpx; margin-top: 32rpx; }
.modal-cancel { flex: 1; text-align: center; padding: 20rpx 0; border: 1rpx solid #aaaaaa; border-radius: 999rpx; font-size: 28rpx; color: var(--store-regular); }
.complete-submit { flex: 1; text-align: center; }
.actions-disabled { opacity: .6; pointer-events: none; }
</style>
