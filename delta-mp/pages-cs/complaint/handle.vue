<template>
  <view class="handle-page" v-if="complaint">
    <!-- 投诉信息 -->
    <view class="card">
      <view class="card-top">
        <text class="type">{{ COMPLAINT_TYPE_TEXT[complaint.type] || complaint.type }}</text>
        <StatusTag :status="complaint.status" :text-map="COMPLAINT_STATUS_TEXT" :color-map="COMPLAINT_STATUS_COLOR" />
      </view>
      <text class="content">{{ complaint.content }}</text>
      <view v-if="showEvidenceAttachments" class="attachments">
        <text class="attachment-label">证据附件</text>
        <view v-if="evidenceItems.length" class="images">
          <view v-for="(att, i) in evidenceItems" :key="`evidence-${i}`" class="attachment-cell">
            <AppImage v-if="att.state === 'ready'" :src="att.src" mode="aspectFill" class="img" @click="previewAttachment(att)" @error="markAttachmentUnavailable(att)" />
            <view v-else class="attachment-unavailable">{{ attachmentStateText(att) }}</view>
          </view>
        </view>
        <view v-if="legacyImageCount" class="attachment-unavailable">附件不可用</view>
      </view>
      <view class="info-row"><text class="label">投诉人</text><text class="value">{{ userNickname || complaint.userId }}</text></view>
      <view v-if="order" class="info-row"><text class="label">订单号</text><text class="value">{{ order.orderNo }}</text></view>
      <view v-if="order" class="info-row"><text class="label">订单金额</text><text class="value gold">¥{{ Number(order.amount).toFixed(2) }}</text></view>
      <view v-if="playerNickname" class="info-row"><text class="label">接单员</text><text class="value">{{ playerNickname }}</text></view>
      <view class="info-row"><text class="label">期望结果</text><text class="value">{{ complaint.expectedResult }}</text></view>
      <view class="info-row"><text class="label">提交时间</text><text class="value">{{ formatTime(complaint.createdAt) }}</text></view>
    </view>

    <view v-if="appealItems.length" class="card">
      <text class="card-title">申诉附件</text>
      <view class="images">
        <view v-for="(att, i) in appealItems" :key="`appeal-${i}`" class="attachment-cell">
          <AppImage v-if="att.state === 'ready'" :src="att.src" mode="aspectFill" class="img" @click="previewAttachment(att)" @error="markAttachmentUnavailable(att)" />
          <view v-else class="attachment-unavailable">{{ attachmentStateText(att) }}</view>
        </view>
      </view>
    </view>

    <!-- 受理操作 -->
    <view v-if="complaint.status==='PENDING'" class="card">
      <view class="submit-btn" @click="doAccept">受理投诉</view>
    </view>

    <!-- 仲裁操作 -->
    <view v-if="complaint.status==='PROCESSING' || complaint.status==='APPEALING'" class="card">
      <text class="card-title">仲裁处理</text>
      <view class="form-item">
        <text class="label">仲裁结果</text>
        <view class="result-options">
          <view class="opt" :class="{active: resolveForm.result==='FULL_REFUND'}" @click="resolveForm.result='FULL_REFUND'">全额退款</view>
          <view class="opt" :class="{active: resolveForm.result==='PARTIAL_REFUND'}" @click="resolveForm.result='PARTIAL_REFUND'">部分退款</view>
          <view class="opt" :class="{active: resolveForm.result==='REDO'}" @click="resolveForm.result='REDO'">重新服务</view>
          <view class="opt" :class="{active: resolveForm.result==='REJECT'}" @click="resolveForm.result='REJECT'">驳回投诉</view>
        </view>
      </view>
      <view v-if="resolveForm.result==='PARTIAL_REFUND'" class="form-item">
        <text class="label">退款金额</text>
        <input type="digit" v-model="resolveForm.refundAmount" placeholder="0.00" class="input" />
      </view>
      <view class="form-item">
        <text class="label">接单员处罚</text>
        <view class="result-options">
          <view class="opt" :class="{active: resolveForm.playerPenalty==='NONE'}" @click="resolveForm.playerPenalty='NONE'">无</view>
          <view class="opt" :class="{active: resolveForm.playerPenalty==='WARNING'}" @click="resolveForm.playerPenalty='WARNING'">警告</view>
          <view class="opt" :class="{active: resolveForm.playerPenalty==='FREEZE'}" @click="resolveForm.playerPenalty='FREEZE'">冻结账号</view>
        </view>
      </view>
      <view class="form-item">
        <text class="label">处理说明</text>
        <textarea v-model="resolveForm.resultReason" placeholder="请输入处理说明" class="textarea" />
      </view>
      <view class="submit-btn" @click="doResolve">提交仲裁</view>
    </view>

    <!-- 已处理结果 -->
    <view v-if="complaint.result" class="card result-card">
      <text class="card-title">处理结果</text>
      <view class="info-row"><text class="label">仲裁结果</text><text class="value">{{ RESULT_TEXT[complaint.result] || complaint.result }}</text></view>
      <view v-if="complaint.resultReason" class="info-row"><text class="label">处理说明</text><text class="value">{{ complaint.resultReason }}</text></view>
      <view v-if="complaint.refundAmount" class="info-row"><text class="label">退款金额</text><text class="value" style="color:#ee0a24;font-weight:bold">¥{{ Number(complaint.refundAmount).toFixed(2) }}</text></view>
      <view v-if="complaint.playerPenalty && complaint.playerPenalty !== 'NONE'" class="info-row"><text class="label">接单员处罚</text><text class="value">{{ PENALTY_TEXT[complaint.playerPenalty] || complaint.playerPenalty }}</text></view>
      <view v-if="complaint.resolvedAt" class="info-row"><text class="label">处理时间</text><text class="value">{{ formatTime(complaint.resolvedAt) }}</text></view>
    </view>
  </view>
</template>

<script setup>
import AppImage from '@/components/AppImage.vue'
import { computed, onBeforeUnmount, ref, reactive } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import StatusTag from '@/components/StatusTag.vue'
import {
  getCsComplaintAttachment,
  getCsComplaintDetail,
  processCsComplaint,
  releaseCsComplaintAttachmentUrl,
  resolveCsComplaint
} from '@/pages-cs/api/cs'

const COMPLAINT_TYPE_TEXT = { SERVICE_QUALITY: '服务质量', ACCOUNT_ISSUE: '账号问题', DELAY: '进度延迟', FRAUD: '欺诈', OTHER: '其他' }
const COMPLAINT_STATUS_TEXT = { PENDING: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', REJECTED: '已驳回', APPEALING: '申诉中' }
const COMPLAINT_STATUS_COLOR = { PENDING: '#ee0a24', PROCESSING: '#ff9900', RESOLVED: '#07c160', REJECTED: '#999', APPEALING: '#ee0a24' }
const RESULT_TEXT = { FULL_REFUND: '全额退款', PARTIAL_REFUND: '部分退款', REJECT: '驳回投诉', REDO: '重新服务' }
const PENALTY_TEXT = { NONE: '无', WARNING: '警告', FREEZE: '冻结账号' }

const complaint = ref(null)
const order = ref(null)
const userNickname = ref('')
const playerNickname = ref('')
const complaintId = ref('')
const resolveForm = reactive({ result: 'FULL_REFUND', refundAmount: '', resultReason: '', playerPenalty: 'NONE' })
const evidenceItems = ref([])
const appealItems = ref([])
const legacyImageCount = ref(0)
const createdObjectUrls = new Set()
let attachmentGeneration = 0
let pageDisposed = false
const showEvidenceAttachments = computed(() => evidenceItems.value.length > 0 || legacyImageCount.value > 0)
const readyAttachmentSources = computed(() => [
  ...evidenceItems.value,
  ...appealItems.value
].filter(item => item.state === 'ready' && item.src).map(item => item.src))

onLoad(async (opts) => {
  complaintId.value = opts.id
  await loadDetail(opts.id)
})

async function loadDetail(id) {
  const generation = ++attachmentGeneration
  releaseAttachmentUrls()
  evidenceItems.value = []
  appealItems.value = []
  legacyImageCount.value = 0
  try {
    const res = await getCsComplaintDetail(id)
    if (pageDisposed || generation !== attachmentGeneration) return
    const data = res.data || {}
    // 后端返回 ComplaintDetailVO: { complaint, order, userNickname, playerNickname, ... }
    const complaintData = data.complaint || data
    complaint.value = complaintData
    order.value = data.order || null
    userNickname.value = data.userNickname || ''
    playerNickname.value = data.playerNickname || ''
    evidenceItems.value = attachmentItems([
      ...(Array.isArray(complaintData.attachments) ? complaintData.attachments : []),
      ...(Array.isArray(data.attachments) ? data.attachments : [])
    ])
    appealItems.value = attachmentItems([
      ...(Array.isArray(complaintData.appealAttachments) ? complaintData.appealAttachments : []),
      ...(Array.isArray(data.appealAttachments) ? data.appealAttachments : [])
    ])
    legacyImageCount.value = evidenceItems.value.length ? 0 : legacyImageCountOf(complaintData)
    await Promise.all([
      hydrateAttachments(evidenceItems.value, generation),
      hydrateAttachments(appealItems.value, generation)
    ])
  } catch (error) {
    if (!pageDisposed && generation === attachmentGeneration) {
      uni.showToast({ title: error?.msg || error?.message || '投诉详情加载失败', icon: 'none' })
    }
  }
}

function normalizeAttachmentToken(value) {
  const token = typeof value === 'string' ? value : value?.token
  return typeof token === 'string' ? token.trim() : ''
}

function attachmentItems(value) {
  if (!Array.isArray(value)) return []
  const seen = new Set()
  return value.map(item => ({
    token: normalizeAttachmentToken(item),
    kind: typeof item?.kind === 'string' ? item.kind.trim() : '',
    state: '',
    src: ''
  })).filter(item => {
    if (item.kind.toUpperCase() === 'UNAVAILABLE') return true
    if (!item.token || item.token === '.' || item.token === '..' || seen.has(item.token)) return false
    seen.add(item.token)
    return true
  }).map(item => ({ ...item, state: item.kind.toUpperCase() === 'UNAVAILABLE' || !item.token ? 'unavailable' : 'loading' }))
}

function legacyImageCountOf(complaintData) {
  const images = complaintData?.images
  if (Array.isArray(images)) return images.filter(Boolean).length
  if (typeof images !== 'string') return 0
  return images.split(',').filter(item => item.trim()).length
}

function releaseAttachmentUrls() {
  for (const source of createdObjectUrls) releaseCsComplaintAttachmentUrl(source)
  createdObjectUrls.clear()
  for (const item of [...evidenceItems.value, ...appealItems.value]) item.src = ''
}

async function hydrateAttachments(items, generation) {
  await Promise.all(items.map(async item => {
    if (!item.token || item.state === 'unavailable') return
    try {
      const source = await getCsComplaintAttachment(complaintId.value, item.token)
      if (pageDisposed || generation !== attachmentGeneration) {
        releaseCsComplaintAttachmentUrl(source)
        return
      }
      createdObjectUrls.add(source)
      item.src = source
      item.state = 'ready'
    } catch (error) {
      if (pageDisposed || generation !== attachmentGeneration) return
      item.state = error?.attachmentUnsupported ? 'unsupported' : 'unavailable'
    }
  }))
}

function attachmentStateText(item) {
  if (item.state === 'unsupported') return '当前环境无法安全预览附件，请在电脑端查看'
  if (item.state === 'unavailable') return '附件不可用'
  return '附件加载中'
}

function markAttachmentUnavailable(item) {
  if (item.src) {
    createdObjectUrls.delete(item.src)
    releaseCsComplaintAttachmentUrl(item.src)
    item.src = ''
  }
  item.state = 'unavailable'
}

function previewAttachment(item) {
  if (item.state !== 'ready' || !item.src) return
  uni.previewImage({ urls: readyAttachmentSources.value, current: item.src })
}

async function doAccept() {
  try {
    await processCsComplaint(complaintId.value, {})
    uni.showToast({ title: '已受理' })
    await loadDetail(complaintId.value)
  } catch (e) {}
}

async function doResolve() {
  if (!resolveForm.resultReason) return uni.showToast({ title: '请输入处理说明', icon: 'none' })
  try {
    await resolveCsComplaint(complaintId.value, {
      result: resolveForm.result,
      refundAmount: resolveForm.result === 'PARTIAL_REFUND' ? Number(resolveForm.refundAmount) || 0 : null,
      resultReason: resolveForm.resultReason,
      playerPenalty: resolveForm.playerPenalty
    })
    uni.showToast({ title: '仲裁完成' })
    setTimeout(() => uni.navigateBack(), 1500)
  } catch (e) {}
}

function formatTime(t) {
  if (!t) return ''
  return t.replace('T', ' ').substring(0, 16)
}

function disposePage() {
  if (pageDisposed) return
  pageDisposed = true
  attachmentGeneration++
  releaseAttachmentUrls()
}

onUnload(disposePage)
onBeforeUnmount(disposePage)
</script>
<style lang="scss" scoped>
.handle-page { background: #ffffff; min-height: var(--app-page-height, 100vh); padding-bottom: 40rpx; }
.card { margin: 20rpx 24rpx; padding: 24rpx; background: rgba(0,0,0,0.04); border-radius: 12rpx; box-sizing: border-box; }
.card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16rpx; .type { font-size: 30rpx; font-weight: bold; } }
.card-title { font-size: 28rpx; font-weight: bold; display: block; margin-bottom: 16rpx; color: #17191c; }
.content { font-size: 28rpx; color: rgba(0,0,0,0.85); line-height: 1.6; display: block; margin-bottom: 16rpx; word-break: break-all; }
.attachments { margin-bottom: 16rpx; }
.attachment-label { display: block; font-size: 24rpx; color: rgba(0,0,0,0.45); margin-bottom: 12rpx; }
.images { display: flex; gap: 12rpx; margin-bottom: 16rpx; flex-wrap: wrap; .img { width: 160rpx; height: 160rpx; border-radius: 8rpx; } }
.attachment-cell { width: 160rpx; height: 160rpx; border-radius: 8rpx; overflow: hidden; }
.attachment-unavailable { width: 160rpx; min-height: 160rpx; box-sizing: border-box; padding: 16rpx; display: flex; align-items: center; justify-content: center; text-align: center; border-radius: 8rpx; background: rgba(0,0,0,0.05); color: rgba(0,0,0,0.45); font-size: 22rpx; line-height: 1.4; }
.info-row { display: flex; padding: 8rpx 0; .label { font-size: 24rpx; color: rgba(0,0,0,0.3); width: 140rpx; flex-shrink: 0; } .value { font-size: 24rpx; color: rgba(0,0,0,0.85); flex: 1; word-break: break-all; &.gold { color: #17191c; font-weight: bold; } } }
.form-item { margin-bottom: 24rpx; .label { font-size: 26rpx; color: rgba(0,0,0,0.85); display: block; margin-bottom: 12rpx; } .input { background: rgba(0,0,0,0.05); padding: 16rpx; border-radius: 8rpx; font-size: 26rpx; color: rgba(0,0,0,0.85); } }
.textarea { background: rgba(0,0,0,0.05); padding: 16rpx; border-radius: 8rpx; font-size: 26rpx; width: 100%; height: 160rpx; color: rgba(0,0,0,0.85); box-sizing: border-box; }
.result-options { display: flex; gap: 16rpx; flex-wrap: wrap; .opt { padding: 12rpx 24rpx; background: rgba(0,0,0,0.05); border-radius: 8rpx; font-size: 26rpx; &.active { background: linear-gradient(135deg, #17191c, #30343a); color: #ffffff; font-weight:bold; } } }
.submit-btn { background: linear-gradient(135deg, #17191c, #30343a); color: #ffffff; font-weight:bold; text-align: center; padding: 20rpx; border-radius: 999rpx; font-size: 28rpx; margin-top: 16rpx; }
.result-card { border: 1rpx solid rgba(23, 25, 28, 0.1); }
</style>
