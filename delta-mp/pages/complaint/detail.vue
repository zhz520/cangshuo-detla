<template>
  <view class="complaint-detail" v-if="complaint">
    <view class="card">
      <view class="info-row">
        <text class="label">投诉类型</text>
        <text class="value">{{ complaint.type }}</text>
      </view>
      <view class="info-row">
        <text class="label">处理状态</text>
        <StatusTag :status="complaint.status" :textMap="COMPLAINT_STATUS_TEXT" :colorMap="complaintColorMap" />
      </view>
      <view class="info-row">
        <text class="label">创建时间</text>
        <text class="value">{{ complaint.createdAt }}</text>
      </view>
    </view>
    <view class="card">
      <text class="card-title">投诉内容</text>
      <text class="content-text">{{ complaint.content }}</text>
      <view v-if="showEvidenceAttachments" class="attachment-section">
        <text class="attachment-label">证据附件</text>
        <view v-if="evidenceItems.length" class="evidence-images">
          <view v-for="(att, idx) in evidenceItems" :key="`evidence-${idx}`" class="attachment-cell">
            <AppImage v-if="att.state === 'ready'" :src="att.src" mode="aspectFill" class="ev-img" @click="previewAttachment(att)" @error="markAttachmentUnavailable(att)" />
            <view v-else class="attachment-unavailable">{{ attachmentStateText(att) }}</view>
          </view>
        </view>
        <view v-if="legacyImageCount" class="attachment-unavailable legacy-attachment">附件不可用</view>
      </view>
      <view v-if="complaint.expectedResult" class="expected">
        <text class="label">期望结果</text>
        <text class="value">{{ complaint.expectedResult }}</text>
      </view>
    </view>
    <!-- 处理结果 -->
    <view class="card" v-if="complaint.resultReason || complaint.result">
      <text class="card-title">处理结果</text>
      <text v-if="complaint.resultReason" class="content-text">{{ complaint.resultReason }}</text>
      <view v-if="complaint.refundAmount" class="info-row">
        <text class="label">退款金额</text>
        <text class="value refund">¥{{ Number(complaint.refundAmount).toFixed(2) }}</text>
      </view>
      <view v-if="complaint.playerPenalty && complaint.playerPenalty !== 'NONE'" class="info-row">
        <text class="label">处罚措施</text>
        <text class="value">{{ { FREEZE: '冻结账号', WARNING: '警告', DEDUCT: '扣款' }[complaint.playerPenalty] || complaint.playerPenalty }}</text>
      </view>
    </view>
    <view class="card" v-if="appealItems.length">
      <text class="card-title">申诉附件</text>
      <view class="evidence-images">
        <view v-for="(att, idx) in appealItems" :key="`appeal-${idx}`" class="attachment-cell">
          <AppImage v-if="att.state === 'ready'" :src="att.src" mode="aspectFill" class="ev-img" @click="previewAttachment(att)" @error="markAttachmentUnavailable(att)" />
          <view v-else class="attachment-unavailable">{{ attachmentStateText(att) }}</view>
        </view>
      </view>
    </view>
    <!-- 申诉按钮 -->
    <view class="actions" v-if="complaint.status === 'RESOLVED'">
      <view class="btn" @click="showAppeal = true">我要申诉</view>
    </view>
    <!-- 申诉弹窗 -->
    <view class="modal-mask" v-if="showAppeal" @click="closeAppeal">
      <view class="modal-body" @click.stop>
        <text class="modal-title">提交申诉</text>
        <textarea v-model="appealContent" placeholder="请说明申诉原因" :maxlength="500" />
        <text class="modal-subtitle">补充证据（选填）</text>
        <ComplaintAttachmentUploader v-model="appealAttachmentIds" :order-id="appealOrderId" :max="6" @uploading="appealUploading = $event" />
        <view class="modal-btns">
          <view class="modal-btn cancel" @click="closeAppeal">取消</view>
          <view class="modal-btn confirm" :class="{ disabled: appealSubmitting }" @click="submitAppeal">{{ appealSubmitting ? '提交中...' : '提交' }}</view>
        </view>
      </view>
    </view>
  </view>
  <!-- 关联订单 -->
  <view class="card" v-if="order">
    <text class="card-title">关联订单</text>
    <view class="info-row">
      <text class="label">订单号</text>
      <text class="value">{{ order.orderNo }}</text>
    </view>
    <view class="info-row">
      <text class="label">商品</text>
      <text class="value">{{ order.productName }}</text>
    </view>
    <view class="info-row">
      <text class="label">订单状态</text>
      <text class="value">{{ order.status }}</text>
    </view>
  </view>
  <!-- 订单进度 -->
  <view class="card" v-if="progress && progress.length">
    <text class="card-title">订单进度</text>
    <view v-for="p in progress" :key="p.id" class="info-row">
      <text class="label">{{ p.createdAt }}</text>
      <text class="value">{{ p.content }}</text>
    </view>
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import ComplaintAttachmentUploader from '@/components/ComplaintAttachmentUploader.vue'
import { computed, onBeforeUnmount, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import StatusTag from '@/components/StatusTag.vue'
import {
  appealComplaint,
  getComplaintAttachment,
  getComplaintDetail,
  legacyComplaintImageCount,
  normalizeAttachmentList,
  releaseComplaintAttachmentUrl
} from '@/api/complaint'
import { COMPLAINT_STATUS_TEXT } from '@/utils/constants'

const complaintColorMap = { PENDING: '#ff9900', PROCESSING: '#ff4544', RESOLVED: '#07c160', APPEALING: '#ee0a24', APPEAL_RESOLVED: '#07c160' }
const complaint = ref(null)
const order = ref(null)
const progress = ref([])
const complaintId = ref(0)
const showAppeal = ref(false)
const appealContent = ref('')
const appealAttachmentIds = ref([])
const appealUploading = ref(false)
const appealSubmitting = ref(false)
const evidenceItems = ref([])
const appealItems = ref([])
const legacyImageCount = ref(0)
const createdObjectUrls = new Set()
let attachmentGeneration = 0
let pageDisposed = false

const showEvidenceAttachments = computed(() => evidenceItems.value.length > 0 || legacyImageCount.value > 0)
const appealOrderId = computed(() => complaint.value?.orderId || order.value?.id || '')
const readyAttachmentSources = computed(() => [
  ...evidenceItems.value,
  ...appealItems.value
].filter(item => item.state === 'ready' && item.src).map(item => item.src))

function attachmentItems(value) {
  return normalizeAttachmentList(value).map((item, index) => ({
    ...item,
    key: `${index}`,
    state: item.kind.toUpperCase() === 'UNAVAILABLE' || !item.token ? 'unavailable' : 'loading',
    src: ''
  }))
}

function releaseAttachmentUrls() {
  for (const source of createdObjectUrls) releaseComplaintAttachmentUrl(source)
  createdObjectUrls.clear()
  for (const item of [...evidenceItems.value, ...appealItems.value]) {
    item.src = ''
  }
}

async function hydrateAttachments(items, generation) {
  await Promise.all(items.map(async item => {
    if (!item.token || item.state === 'unavailable') return
    try {
      const source = await getComplaintAttachment(item.token)
      if (pageDisposed || generation !== attachmentGeneration) {
        releaseComplaintAttachmentUrl(source)
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

async function loadDetail(id) {
  const generation = ++attachmentGeneration
  releaseAttachmentUrls()
  evidenceItems.value = []
  appealItems.value = []
  legacyImageCount.value = 0
  try {
    const res = await getComplaintDetail(id)
    if (pageDisposed || generation !== attachmentGeneration) return
    const payload = res.data || {}
    const complaintData = payload.complaint || payload
    const attachmentSource = [
      ...(Array.isArray(complaintData.attachments) ? complaintData.attachments : []),
      ...(Array.isArray(payload.attachments) ? payload.attachments : [])
    ]
    const appealSource = [
      ...(Array.isArray(complaintData.appealAttachments) ? complaintData.appealAttachments : []),
      ...(Array.isArray(payload.appealAttachments) ? payload.appealAttachments : [])
    ]

    complaint.value = complaintData
    order.value = payload.order || null
    progress.value = payload.progress || []
    evidenceItems.value = attachmentItems(attachmentSource)
    appealItems.value = attachmentItems(appealSource)
    legacyImageCount.value = evidenceItems.value.length ? 0 : legacyComplaintImageCount(complaintData)

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

onLoad(opts => {
  complaintId.value = opts.id
  loadDetail(opts.id)
})

function closeAppeal() {
  if (appealSubmitting.value) return
  showAppeal.value = false
  appealContent.value = ''
  appealAttachmentIds.value = []
}

async function submitAppeal() {
  if (appealSubmitting.value) return
  if (appealUploading.value) return uni.showToast({ title: '附件上传中，请稍候', icon: 'none' })
  if (!appealContent.value.trim()) return uni.showToast({ title: '请输入申诉原因', icon: 'none' })
  appealSubmitting.value = true
  try {
    await appealComplaint(complaintId.value, {
      appealReason: appealContent.value.trim(),
      attachmentIds: [...appealAttachmentIds.value]
    })
    showAppeal.value = false
    appealContent.value = ''
    appealAttachmentIds.value = []
    uni.showToast({ title: '申诉已提交' })
    await loadDetail(complaintId.value)
  } catch (error) {
    uni.showToast({ title: error?.msg || error?.message || '申诉提交失败', icon: 'none' })
  } finally {
    appealSubmitting.value = false
  }
}

function attachmentStateText(item) {
  if (item.state === 'unsupported') return '当前环境无法安全预览附件，请在电脑端查看'
  if (item.state === 'unavailable') return '附件不可用'
  return '附件加载中'
}

function markAttachmentUnavailable(item) {
  if (item.src) {
    createdObjectUrls.delete(item.src)
    releaseComplaintAttachmentUrl(item.src)
    item.src = ''
  }
  item.state = 'unavailable'
}

function previewAttachment(item) {
  if (item.state !== 'ready' || !item.src) return
  uni.previewImage({ urls: readyAttachmentSources.value, current: item.src })
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
.complaint-detail { background: var(--store-soft); min-height: var(--app-page-height, 100vh); }
.card { margin-top: 20rpx; padding: 24rpx; background: var(--store-surface); border: 1rpx solid var(--store-line); }
.card-title { font-size: 28rpx; font-weight: bold; display: block; margin-bottom: 16rpx; color: var(--store-text); }
.info-row { display: flex; align-items: center; padding: 8rpx 0; .label { font-size: 26rpx; color: var(--store-regular); width: 140rpx; } .value { font-size: 26rpx; color: var(--store-text); &.refund { color: #d44955; font-weight: bold; } } }
.content-text { font-size: 28rpx; color: var(--store-text); line-height: 1.6; display: block; }
.attachment-section { margin-top: 16rpx; }
.attachment-label { display: block; font-size: 26rpx; color: var(--store-regular); margin-bottom: 12rpx; }
.evidence-images { display: flex; gap: 12rpx; margin-top: 16rpx; flex-wrap: wrap; }
.attachment-cell { width: 160rpx; height: 160rpx; border-radius: 8rpx; overflow: hidden; }
.ev-img { width: 160rpx; height: 160rpx; border-radius: 8rpx; }
.attachment-unavailable { width: 160rpx; min-height: 160rpx; box-sizing: border-box; padding: 16rpx; display: flex; align-items: center; justify-content: center; text-align: center; border-radius: 8rpx; background: var(--store-soft); color: var(--store-regular); font-size: 22rpx; line-height: 1.4; }
.legacy-attachment { margin-top: 16rpx; }
.expected { margin-top: 16rpx; padding-top: 16rpx; border-top: 1rpx solid var(--store-line); }
.actions { padding: 40rpx 24rpx; }
.btn { height: 88rpx; line-height: 88rpx; text-align: center; background: var(--store-primary); color: #ffffff; font-weight: bold; font-size: 32rpx; border-radius: 999rpx; }
.modal-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 999; }
.modal-body { width: 80%; background: var(--store-surface); border: 1rpx solid var(--store-line); border-radius: 16rpx; padding: 40rpx; textarea { width: 100%; height: 200rpx; background: var(--store-soft); border: 1rpx solid var(--store-line); border-radius: 8rpx; padding: 20rpx; font-size: 28rpx; margin: 20rpx 0; color: var(--store-text); } }
.modal-title { font-size: 32rpx; font-weight: bold; text-align: center; display: block; color: var(--store-text); }
.modal-subtitle { font-size: 26rpx; color: var(--store-regular); display: block; margin: 8rpx 0 12rpx; }
.modal-btns { display: flex; gap: 24rpx; }
.modal-btn { flex: 1; height: 76rpx; line-height: 76rpx; text-align: center; border-radius: 999rpx; font-size: 28rpx; &.cancel { background: var(--store-soft); color: var(--store-regular); } &.confirm { background: var(--store-primary); color: #ffffff; font-weight: bold; &.disabled { opacity: 0.6; } } }
</style>
