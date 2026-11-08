<template>
  <div class="page-container refund-page">
    <el-card shadow="never" class="workflow-card">
      <div class="page-head">
        <div>
          <h2>退款审批</h2>
          <p>客服核验并提交初审，管理员终审后才会发起原路退款。</p>
        </div>
        <div class="head-actions">
          <el-tag :type="isAdmin ? 'danger' : 'primary'" effect="light">{{ isAdmin ? '管理员终审' : '客服初审' }}</el-tag>
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button type="primary" @click="openCreate">创建退款申请</el-button>
        </div>
      </div>

      <el-alert
        v-if="!isAdmin"
        title="客服只能核验和提交初审建议，不能直接执行退款。"
        type="warning"
        :closable="false"
        show-icon
      />

      <el-form :inline="true" class="filter-form" @submit.prevent="search">
        <el-form-item label="订单号">
          <el-input v-model="query.orderNo" clearable placeholder="输入订单号" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 170px">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="rows" stripe empty-text="暂无退款申请">
        <el-table-column prop="refundNo" label="退款单号" min-width="170" show-overflow-tooltip />
        <el-table-column prop="orderNo" label="订单号" min-width="170" show-overflow-tooltip />
        <el-table-column label="退款金额" width="110" align="right">
          <template #default="{ row }">¥{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="支付方式" width="125">
          <template #default="{ row }">{{ paymentMethodLabel(row.paymentMethod) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="reason" label="退款原因" min-width="210" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="申请时间" width="168" />
        <el-table-column label="操作" width="260" :fixed="isCompact ? false : 'right'">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="!isAdmin && row.status === 'REQUESTED'" link type="primary" @click="startReview(row)">开始初审</el-button>
            <el-button v-if="canSubmitReview(row)" link type="warning" @click="openReview(row)">提交初审</el-button>
            <template v-if="isAdmin">
              <el-button v-if="row.status === 'CS_REVIEWED'" link type="success" @click="approve(row)">终审通过</el-button>
              <el-button v-if="canReject(row)" link type="danger" @click="reject(row)">驳回</el-button>
              <el-button v-if="row.status === 'FAILED'" link type="warning" @click="retry(row)">重试退款</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <Pagination
        :total="total"
        v-model:page="query.pageNum"
        v-model:limit="query.pageSize"
        @pagination="load"
      />
    </el-card>

    <el-dialog v-model="createVisible" title="创建退款申请" :width="isCompact ? '94%' : '560px'" destroy-on-close>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="订单 ID" prop="orderId">
          <el-input-number v-model="createForm.orderId" :min="1" :precision="0" controls-position="right" style="width:100%" />
        </el-form-item>
        <el-form-item label="退款金额" prop="amount">
          <el-input-number v-model="createForm.amount" :min="0.01" :precision="2" controls-position="right" placeholder="留空则为订单全额" style="width:100%" />
          <div class="field-hint">只能小于等于原订单实付金额；部分退款也需经过同样审批。</div>
        </el-form-item>
        <el-form-item label="退款原因" prop="reason">
          <el-input v-model="createForm.reason" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="说明退款原因及核验情况" />
        </el-form-item>
        <el-form-item label="幂等请求号（可选）">
          <el-input v-model="createForm.clientRequestId" maxlength="64" placeholder="网络重试时保持相同值，避免重复提交" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">提交申请</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="提交客服初审" :width="isCompact ? '94%' : '520px'">
      <el-form label-position="top">
        <el-form-item label="初审建议">
          <el-radio-group v-model="reviewForm.recommendation">
            <el-radio-button value="APPROVE">建议通过</el-radio-button>
            <el-radio-button value="REJECT">建议驳回</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="reviewForm.recommendation === 'REJECT' ? '驳回建议说明（必填）' : '初审说明'">
          <el-input v-model="reviewForm.note" type="textarea" :rows="4" maxlength="500" show-word-limit />
        </el-form-item>
        <el-alert title="初审建议不会修改支付，也不会结束申请；最终由管理员决定。" type="info" :closable="false" />
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewing" @click="submitReview">提交初审</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="退款申请详情" :size="isCompact ? '94%' : '560px'">
      <LoadFeedback :loading="detailLoading" :error="detailError" label="退款详情" @retry="retryDetail" />
      <template v-if="detail && !detailLoading">
        <div class="amount-panel">
          <span>申请退款</span>
          <strong>¥{{ money(detail.amount) }}</strong>
          <el-tag :type="statusType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
        </div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="退款单号">{{ detail.refundNo }}</el-descriptions-item>
          <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="支付方式">{{ paymentMethodLabel(detail.paymentMethod) }}</el-descriptions-item>
          <el-descriptions-item label="申请原因">{{ detail.reason }}</el-descriptions-item>
          <el-descriptions-item label="申请前订单状态">{{ detail.orderStatusBefore }}</el-descriptions-item>
          <el-descriptions-item label="客服初审">{{ reviewText(detail) }}</el-descriptions-item>
          <el-descriptions-item label="管理员终审">{{ adminText(detail) }}</el-descriptions-item>
          <el-descriptions-item label="渠道退款单号">{{ detail.channelRefundNo || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.lastError" label="失败原因">
            <span class="danger-text">{{ detail.lastError }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ detail.submittedAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ detail.completedAt || '-' }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="audit-title">操作审计</h4>
        <el-timeline v-if="auditRows.length">
          <el-timeline-item v-for="item in auditRows" :key="item.id" :timestamp="item.created_at" placement="top">
            <strong>{{ auditAction(item.action) }}</strong>
            <span class="audit-operator">{{ item.operator_type }} {{ item.operator_id || '' }}</span>
            <div v-if="item.note" class="audit-note">{{ item.note }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无审计记录" :image-size="60" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import LoadFeedback from '@/components/LoadFeedback.vue'
import { useResponsive } from '@/utils/responsive'
import { useUserStore } from '@/stores/user'
import {
  adminRefundApprove,
  adminRefundDetail,
  adminRefundList,
  adminRefundReject,
  adminRefundRetry,
  csRefundCreate,
  csRefundDetail,
  csRefundAudit,
  csRefundList,
  csRefundStartReview,
  csRefundSubmitReview
} from '@/api/business'

const { isCompact } = useResponsive()
const route = useRoute()
const userStore = useUserStore()
const isAdmin = computed(() => String(userStore.role).toLowerCase() === 'admin')

const statusOptions = [
  ['REQUESTED', '待客服初审'],
  ['CS_REVIEWING', '客服核验中'],
  ['CS_REVIEWED', '待管理员终审'],
  ['ADMIN_APPROVED', '管理员已批准'],
  ['PROCESSING', '退款处理中'],
  ['REFUNDED', '已退款'],
  ['FAILED', '退款失败'],
  ['ADMIN_REJECTED', '管理员已驳回'],
  ['REJECTED', '已拒绝']
].map(([value, label]) => ({ value, label }))
const statusMap = Object.fromEntries(statusOptions.map(item => [item.value, item.label]))

const query = reactive({ pageNum: 1, pageSize: 10, status: '', orderNo: '' })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
let listVersion = 0

function money(value) { return Number(value || 0).toFixed(2) }
function statusLabel(value) { return statusMap[value] || value || '-' }
function statusType(value) {
  return ({ REQUESTED: 'warning', CS_REVIEWING: 'warning', CS_REVIEWED: 'primary', ADMIN_APPROVED: 'success', PROCESSING: 'primary', REFUNDED: 'success', FAILED: 'danger', ADMIN_REJECTED: 'info', REJECTED: 'info' })[value] || 'info'
}
function paymentMethodLabel(value) {
  return ({ BALANCE: '余额', WECHAT_MINIAPP: '微信小程序', WECHAT_OFFICIAL: '微信公众号', WECHAT_H5: '微信 H5', WECHAT: '微信支付' })[value] || value || '-'
}

async function load() {
  const version = ++listVersion
  loading.value = true
  try {
    const result = await (isAdmin.value ? adminRefundList : csRefundList)({ ...query })
    if (version !== listVersion) return
    rows.value = result.data?.records || []
    total.value = Number(result.data?.total || 0)
  } finally {
    if (version === listVersion) loading.value = false
  }
}
function search() { query.pageNum = 1; load() }
function reset() { query.status = ''; query.orderNo = ''; query.pageNum = 1; load() }

const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref()
const createForm = reactive({ orderId: undefined, amount: undefined, reason: '', clientRequestId: '' })
const createRules = {
  orderId: [{ required: true, message: '请输入订单 ID', trigger: 'blur' }],
  reason: [{ required: true, min: 2, max: 500, message: '退款原因须为 2 至 500 字', trigger: 'blur' }]
}
function openCreate() {
  Object.assign(createForm, { orderId: undefined, amount: undefined, reason: '', clientRequestId: `refund-${Date.now()}` })
  createVisible.value = true
}
async function submitCreate() {
  await createFormRef.value?.validate()
  creating.value = true
  try {
    await csRefundCreate(createForm.orderId, {
      amount: createForm.amount,
      reason: createForm.reason.trim(),
      clientRequestId: createForm.clientRequestId.trim() || undefined
    })
    ElMessage.success('退款申请已创建')
    createVisible.value = false
    await load()
  } finally {
    creating.value = false
  }
}

const reviewVisible = ref(false)
const reviewing = ref(false)
const activeId = ref(null)
const reviewForm = reactive({ recommendation: 'APPROVE', note: '' })
function canSubmitReview(row) {
  return !isAdmin.value && ['REQUESTED', 'CS_REVIEWING'].includes(row.status)
}
function openReview(row) {
  activeId.value = row.id
  Object.assign(reviewForm, { recommendation: 'APPROVE', note: '' })
  reviewVisible.value = true
}
async function startReview(row) {
  await csRefundStartReview(row.id)
  ElMessage.success('已开始初审')
  await load()
}
async function submitReview() {
  if (reviewForm.recommendation === 'REJECT' && reviewForm.note.trim().length < 2) {
    ElMessage.warning('建议驳回时请填写至少2字说明')
    return
  }
  reviewing.value = true
  try {
    await csRefundSubmitReview(activeId.value, {
      recommendation: reviewForm.recommendation,
      note: reviewForm.note.trim()
    })
    ElMessage.success('初审已提交，等待管理员终审')
    reviewVisible.value = false
    await load()
  } finally {
    reviewing.value = false
  }
}

function canReject(row) {
  return ['REQUESTED', 'CS_REVIEWING', 'CS_REVIEWED', 'FAILED'].includes(row.status)
}
async function approve(row) {
  const { value } = await ElMessageBox.prompt(
    `确认终审通过退款 ¥${money(row.amount)}？通过后会立即进入支付渠道退款。`,
    '管理员终审通过',
    { confirmButtonText: '确认退款', cancelButtonText: '取消', inputPlaceholder: '终审说明（可选）', inputValue: '' }
  )
  await adminRefundApprove(row.id, { note: value || undefined })
  ElMessage.success('终审通过，退款已提交支付渠道')
  await load()
}
async function reject(row) {
  const { value } = await ElMessageBox.prompt('请输入驳回原因（至少2字）', '驳回退款申请', {
    confirmButtonText: '确认驳回',
    cancelButtonText: '取消',
    inputType: 'textarea',
    inputValidator: value => value?.trim().length >= 2 || '驳回原因至少2字'
  })
  await adminRefundReject(row.id, { note: value.trim() })
  ElMessage.success('退款申请已驳回')
  await load()
}
async function retry(row) {
  await ElMessageBox.confirm('确认按原退款单号重试支付渠道退款？', '重试退款', { type: 'warning' })
  await adminRefundRetry(row.id)
  ElMessage.success('退款重试已提交')
  await load()
}

const detailVisible = ref(false)
const detail = ref(null)
const auditRows = ref([])
const detailLoading = ref(false)
const detailError = ref('')
const activeDetailId = ref(null)
let detailVersion = 0
async function openDetail(row) {
  activeDetailId.value = row.id
  detailVisible.value = true
  await loadDetail(row.id)
}
async function loadDetail(id) {
  if (!id) return
  const version = ++detailVersion
  detail.value = null
  auditRows.value = []
  detailError.value = ''
  detailLoading.value = true
  try {
    const detailRequest = isAdmin.value ? adminRefundDetail(id) : csRefundDetail(id)
    const [detailResult, auditResult] = await Promise.all([detailRequest, csRefundAudit(id)])
    if (version !== detailVersion) return
    detail.value = detailResult.data
    auditRows.value = auditResult.data || []
  } catch (error) {
    if (version === detailVersion) detailError.value = error?.message || '退款详情加载失败'
  } finally {
    if (version === detailVersion) detailLoading.value = false
  }
}
function retryDetail() { loadDetail(activeDetailId.value) }
function auditAction(value) {
  return ({ SUBMIT: '提交申请', CS_START_REVIEW: '客服开始初审', CS_SUBMIT_REVIEW: '客服提交初审',
    ADMIN_APPROVE: '管理员终审通过', ADMIN_REJECT: '管理员驳回', PROCESSING: '提交支付渠道',
    CHANNEL_SUCCESS: '渠道退款成功', CHANNEL_FAILED: '渠道退款失败', ADMIN_RETRY: '管理员重试' })[value] || value
}
function reviewText(row) {
  if (!row.csRecommendation) return '尚未提交'
  return `${row.csRecommendation === 'APPROVE' ? '建议通过' : '建议驳回'}${row.csNote ? '：' + row.csNote : ''}`
}
function adminText(row) {
  if (!row.adminDecision) return '尚未终审'
  return `${row.adminDecision === 'APPROVED' ? '已通过' : '已驳回'}${row.adminNote ? '：' + row.adminNote : ''}`
}

onMounted(() => {
  query.orderNo = typeof route.query.orderNo === 'string' ? route.query.orderNo.slice(0, 64) : ''
  query.status = typeof route.query.status === 'string' ? route.query.status.slice(0, 32) : ''
  load()
})
</script>

<style scoped>
.refund-page { background: #f5f7fa; min-height: 100%; }
.workflow-card { border-radius: 16px; }
.page-head { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 18px; }
.page-head h2 { margin: 0 0 6px; font-size: 22px; color: #111827; }
.page-head p { margin: 0; color: #6b7280; font-size: 13px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.filter-form { margin-top: 18px; }
.field-hint { color: #909399; font-size: 12px; line-height: 1.5; margin-top: 4px; }
.amount-panel { display: flex; align-items: baseline; gap: 12px; padding: 18px; margin-bottom: 18px; border-radius: 12px; background: linear-gradient(135deg, #f8fafc, #eef2ff); }
.amount-panel span { color: #64748b; }
.amount-panel strong { font-size: 28px; color: #111827; }
.danger-text { color: #dc2626; }
.audit-title { margin: 24px 0 14px; color: #111827; }
.audit-operator { margin-left: 8px; color: #909399; font-size: 12px; }
.audit-note { margin-top: 4px; color: #606266; font-size: 13px; }
@media (max-width: 720px) {
  .page-head { flex-direction: column; }
  .head-actions { width: 100%; }
  .head-actions .el-button { flex: 1; }
}
</style>
