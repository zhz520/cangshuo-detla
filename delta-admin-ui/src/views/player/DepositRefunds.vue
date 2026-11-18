<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="refund-header">
          <span>押金退款</span>
          <span class="refund-hint">打手申请后由客服审核，通过后按原支付渠道退回。</span>
        </div>
      </template>
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item><el-input v-model="query.keyword" placeholder="退款单号 / 打手" clearable @keyup.enter="search" /></el-form-item>
        <el-form-item>
          <el-select v-model="query.status" placeholder="状态" clearable style="width: 150px">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="createdAt" label="申请时间" width="170" />
        <el-table-column label="打手" min-width="140">
          <template #default="{ row }"><div>{{ row.playerName || '-' }}</div><div class="sub">ID: {{ row.playerId }}</div></template>
        </el-table-column>
        <el-table-column prop="paymentNo" label="押金支付单号" min-width="180" show-overflow-tooltip />
        <el-table-column prop="amount" label="退款金额" width="110"><template #default="{ row }">¥{{ money(row.amount) }}</template></el-table-column>
        <el-table-column prop="reason" label="申请原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="reviewNote" label="审核说明" min-width="160" show-overflow-tooltip />
        <el-table-column prop="reviewedAt" label="审核时间" width="170" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button link type="success" :disabled="readOnly" @click="openReview(row, true)">通过</el-button>
              <el-button link type="danger" :disabled="readOnly" @click="openReview(row, false)">驳回</el-button>
            </template>
            <el-button v-if="row.retryable" link type="primary" :disabled="readOnly" @click="retry(row)">重试</el-button>
            <span v-if="row.status === 'REFUNDED' || row.status === 'CANCELLED' || row.status === 'REJECTED'" class="sub">已结束</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!loading && !list.length" class="empty">暂无押金退款申请</div>
      <Pagination :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="fetchData" />
    </el-card>

    <el-dialog v-model="reviewVisible" :title="approve ? '通过押金退款' : '驳回押金退款'" width="min(460px, 94vw)">
      <div v-if="current" class="review-summary">
        <span>{{ current.playerName }} · ¥{{ money(current.amount) }}</span>
        <span class="sub">{{ current.reason }}</span>
      </div>
      <el-input v-model="note" type="textarea" :rows="3" maxlength="500" show-word-limit :placeholder="approve ? '审核说明（选填）' : '请填写驳回原因'" />
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button :type="approve ? 'primary' : 'danger'" :loading="submitting" :disabled="!approve && !note.trim()" @click="submitReview">{{ approve ? '确认退款' : '确认驳回' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import { depositRefundList, depositRefundReview, depositRefundRetry } from '@/api/business'
import { isDemoSession } from '@/utils/staff-permissions'

const readOnly = isDemoSession()
const list = ref([]), total = ref(0), loading = ref(false)
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '' })
const statusOptions = [
  { value: 'PENDING', label: '待审核' }, { value: 'REFUNDING', label: '退款中' },
  { value: 'REFUNDED', label: '已退款' }, { value: 'REJECTED', label: '已驳回' },
  { value: 'FAILED', label: '退款失败' }, { value: 'CANCELLED', label: '已取消' }
]
const statusMap = { PENDING: '待审核', REFUNDING: '退款中', REFUNDED: '已退款', REJECTED: '已驳回', FAILED: '退款失败', CANCELLED: '已取消' }
const statusTagMap = { PENDING: 'warning', REFUNDING: 'warning', REFUNDED: 'success', REJECTED: 'danger', FAILED: 'danger', CANCELLED: 'info' }
function statusText(s) { return statusMap[s] || s || '-' }
function statusTag(s) { return statusTagMap[s] || 'info' }
function money(v) { return Number(v || 0).toFixed(2) }
async function fetchData() {
  loading.value = true
  try {
    const res = await depositRefundList(query)
    list.value = res.data?.records || []
    total.value = Number(res.data?.total || 0)
  } finally { loading.value = false }
}
function search() { query.pageNum = 1; fetchData() }
function reset() { query.keyword = ''; query.status = ''; query.pageNum = 1; fetchData() }
const reviewVisible = ref(false), submitting = ref(false), approve = ref(true), note = ref(''), current = ref(null)
function openReview(row, pass) { current.value = row; approve.value = pass; note.value = ''; reviewVisible.value = true }
async function submitReview() {
  if (!current.value || submitting.value) return
  submitting.value = true
  try {
    await depositRefundReview(current.value.id, { approve: approve.value, note: note.value.trim() })
    ElMessage.success(approve.value ? '押金退款已提交' : '已驳回押金退款')
    reviewVisible.value = false
    await fetchData()
  } finally { submitting.value = false }
}
async function retry(row) {
  await depositRefundRetry(row.id)
  ElMessage.success('退款已重试')
  await fetchData()
}
fetchData()
</script>

<style scoped>
.refund-header { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.refund-hint, .sub { color: #98a2b3; font-size: 12px; font-weight: 400; }
.review-summary { display: flex; flex-direction: column; gap: 6px; margin-bottom: 14px; }
.empty { padding: 28px 0; text-align: center; color: #98a2b3; }
</style>
