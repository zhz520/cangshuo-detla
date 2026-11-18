<template>
  <div class="page-container">
    <el-card>
      <template #header><span>订单管理</span></template>
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item><el-input v-model="query.orderNo" placeholder="订单号" aria-label="订单号" clearable @keyup.enter="searchOrders" /></el-form-item>
        <el-form-item>
          <el-select v-model="query.status" placeholder="订单状态" clearable>
            <el-option v-for="(v, k) in orderStatusMap" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isAdmin">
          <el-select v-model="query.csId" placeholder="归属客服" clearable>
            <el-option label="公共池（未分配）" :value="0" />
            <el-option v-for="s in csStaff" :key="s.id" :label="s.nickname || s.username" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" @click="searchOrders">查询</el-button><el-button @click="resetSearch">重置</el-button></el-form-item>
      </el-form>
      <LoadFeedback :error="loadError" label="订单" @retry="fetchData" />
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="orderNo" label="订单号" width="160" show-overflow-tooltip />
        <el-table-column prop="productName" label="商品" min-width="130" show-overflow-tooltip />
        <el-table-column prop="userNickname" label="用户" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.userNickname || ('ID: ' + row.userId) }}</template>
        </el-table-column>
        <el-table-column prop="userPhone" label="客户手机号" width="120">
          <template #default="{ row }">{{ row.userPhone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="playerName" label="打手" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.playerName || (row.playerId ? 'ID: ' + row.playerId : '未指派') }}</template>
        </el-table-column>
        <el-table-column label="归属客服" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.csId ? 'success' : 'info'">{{ row.csName || '公共池' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="90"><template #default="{ row }">¥{{ row.amount }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }"><el-tag :type="orderTagType(row.status)" size="small">{{ orderStatusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="createdAt" label="下单时间" width="145" show-overflow-tooltip />
        <el-table-column label="操作" width="170" :fixed="isCompact ? false : 'right'">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row.id)">详情</el-button>
            <el-button v-if="canAssign(row)" link type="warning" @click="openAssignDialog(row)">{{ row.status === 'ASSIGNED' ? '重新指派' : '指派' }}</el-button>
            <el-button v-if="can('order.refund') && ['ASSIGNED','IN_PROGRESS'].includes(row.status)" link type="danger" @click="handleRefund(row)">退款</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="fetchData" />
    </el-card>
    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="订单详情" width="700px">
      <el-descriptions :column="isNarrow ? 1 : 2" border v-if="detail" v-loading="detailLoading">
        <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag :type="orderTagType(detail.status)" size="small">{{ orderStatusLabel(detail.status) }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="商品">{{ detail.productName }}</el-descriptions-item>
        <el-descriptions-item label="规格">{{ detail.specInfo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="金额">¥{{ detail.amount }}</el-descriptions-item>
        <el-descriptions-item v-if="Number(detail.memberDiscountAmount) > 0" label="会员优惠">{{ detail.memberTier || '会员' }}：-¥{{ Number(detail.memberDiscountAmount).toFixed(2) }}</el-descriptions-item>
        <el-descriptions-item v-if="Number(detail.couponDiscountAmount) > 0" label="优惠券">{{ detail.couponName || '优惠券' }}：-¥{{ Number(detail.couponDiscountAmount).toFixed(2) }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.playerLevelName" label="打手等级">{{ detail.playerLevelName }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.baseAmount != null" label="商品原价">¥{{ detail.baseAmount }}</el-descriptions-item>
        <el-descriptions-item v-if="Number(detail.priceIncreaseRate)>0" label="打手涨价">{{ Number(detail.priceIncreaseRate) }}%（¥{{ detail.priceIncreaseAmount }}）</el-descriptions-item>
        <el-descriptions-item label="用户">{{ detail.userNickname || ('ID: ' + detail.userId) }}</el-descriptions-item>
        <el-descriptions-item label="客户手机号">{{ detail.userPhone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="打手">{{ detail.playerName || (detail.playerId ? 'ID: ' + detail.playerId : '未指派') }}</el-descriptions-item>
        <el-descriptions-item label="归属客服">
          <el-select v-if="isAdmin" v-model="detailCsId" size="small" style="width:150px" @change="saveCsAssignment">
            <el-option label="公共池（未分配）" :value="0" />
            <el-option v-for="s in csStaff" :key="s.id" :label="s.nickname || s.username" :value="s.id" />
          </el-select>
          <span v-else>{{ detail.csName || '公共池' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="游戏账号">{{ detail.gameAccount || '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系方式">{{ detail.contact || '-' }}</el-descriptions-item>
        <el-descriptions-item label="下单时间">{{ detail.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="指派时间">{{ detail.assignTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ detail.completeTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ detail.remark || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="parsedExtraFields(detail).length" label="动态字段" :span="2">
          <div v-for="f in parsedExtraFields(detail)" :key="f.key" style="margin-bottom:4px">
            <span style="color:#909399">{{ f.key }}：</span>{{ f.value }}
          </div>
        </el-descriptions-item>
      </el-descriptions>
      <OrderTeamPanel v-if="detail?.playerId || detail?.teammates?.length" :key="detail.id" :order="detail"
        :admin="isAdmin" :can-manage="canManageTeam" @changed="refreshTeamOrder" />
      <div v-if="detail" class="detail-actions">
        <el-button v-if="can('order.manage') && detail.status === 'COMPLETED'" type="primary" :loading="confirmLoading" @click="handleConfirm(detail)">结单</el-button>
      </div>
      <div v-if="progressList.length" class="progress-section">
        <h4 style="margin:20px 0 12px;color:#303133">订单进度</h4>
        <el-timeline>
          <el-timeline-item v-for="(p, idx) in progressList" :key="idx" :timestamp="p.createdAt" placement="top"
            :type="idx === progressList.length - 1 ? 'primary' : ''">
            <span>{{ p.content || p.description || orderStatusLabel(p.toStatus) || p.toStatus }}</span>
            <span v-if="p.operatorType" style="margin-left:8px;color:#909399;font-size:12px">({{ { USER: '用户', PLAYER: '打手', CS: '客服', SYSTEM: '系统', ADMIN: '管理员' }[p.operatorType] || p.operatorType }})</span>
            <div v-if="p.remark && p.type?.startsWith('TEAMMATE_')" class="team-audit-reason">原因：{{ p.remark }}</div>
            <div v-if="p.images" class="progress-images">
              <el-image lazy
                v-for="(img, imgIdx) in p.images.split(',')"
                :key="img + imgIdx"
                :src="img"
                :preview-src-list="p.images.split(',')"
                :initial-index="imgIdx"
                fit="cover"
                class="progress-image"
              />
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-dialog>
    <!-- 指派弹窗 -->
    <el-dialog v-model="assignVisible" :title="assignMode === 'reassign' ? '重新指派打手' : '指派打手'"
      width="min(600px, calc(100vw - 32px))" :close-on-click-modal="false"
      :close-on-press-escape="!assigning" :show-close="!assigning">
      <p v-if="assignmentCandidates" class="assignment-note">
        {{ assignmentCandidates.requiredLevelId == null ? '订单未限制打手等级' :
          '订单要求等级：' + (assignmentCandidates.requiredLevelName || '等级 #' + assignmentCandidates.requiredLevelId) }}
        · 仅显示在线打手
      </p>
      <el-form :inline="true" @submit.prevent="searchPlayers">
        <el-form-item><el-input v-model="playerSearch" placeholder="按打手昵称搜索" aria-label="打手昵称" clearable :disabled="assigning" @clear="searchPlayers" /></el-form-item>
        <el-form-item><el-button type="primary" native-type="submit" :disabled="assigning">搜索</el-button></el-form-item>
      </el-form>
      <LoadFeedback :error="candidateError" label="候选打手" @retry="fetchPlayers" />
      <p v-if="assignError" class="assignment-error" role="alert">{{ assignError }}</p>
      <el-table v-if="!candidateError" :data="playerList" v-loading="playerLoading" stripe size="small" max-height="300"
        :empty-text="playerLoading ? '正在加载候选打手…' : '暂无符合条件的在线打手'">
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="nickname" label="昵称" min-width="90" show-overflow-tooltip />
        <el-table-column label="等级" min-width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.levelName || (row.levelId == null ? '未分级' : '等级 #' + row.levelId) }}</template>
        </el-table-column>
        <el-table-column label="接单数" width="85">
          <template #default="{ row }">{{ row.activeOrders }} / {{ assignmentCandidates.maxConcurrent }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="playerLoading || assigning || isFull(row)" @click="handleAssign(row)">
              {{ isFull(row) ? '订单已满' : '选择' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="!candidateError && assignmentCandidates?.players?.total > candidatePageSize"
        v-model:current-page="candidatePage" :page-size="candidatePageSize" :total="Number(assignmentCandidates.players.total)"
        layout="prev, pager, next" :pager-count="5" :disabled="assigning" @current-change="fetchPlayers" />
    </el-dialog>
  </div>
</template>
<script setup>
import { useRoute } from 'vue-router'
import {can, isDemoSession} from '@/utils/staff-permissions'
import OrderTeamPanel from './OrderTeamPanel.vue'
import { useResponsive } from '@/utils/responsive'
import { ref, reactive, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useLoadTask } from '@/composables/useLoadTask'
import LoadFeedback from '@/components/LoadFeedback.vue'
import { adminOrderList, adminOrderDetail, adminOrderAssign, adminOrderRefund, adminOrderConfirm, adminOrderProgress, adminOrderReassignCs, csOrderList, csOrderDetail, csOrderAssign, csOrderRefund, csOrderConfirm, csOrderProgress } from '@/api/business'
import { orderAssignmentCandidates } from '@/api/order-assignment'
import { csRoutingStaff } from '@/api/cs-routing'
import { useUserStore } from '@/stores/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
const { isCompact, isNarrow } = useResponsive()

const userStore = useUserStore()
const isAdmin = userStore.role === 'admin'
const canManageTeam = computed(() => !isDemoSession() && can('order.manage')
  && (isAdmin || (detail.value?.csId != null && String(detail.value.csId) === String(userStore.adminId))))
// 兼容后端大写和前端小写枚举
const orderStatusMap = {
  PENDING_PAYMENT: '待支付', PAID: '待接单', ASSIGNED: '已指派', ACCEPTED: '已接单',
  WAITING_TEAMMATE: '组队中', IN_PROGRESS: '进行中', COMPLETED: '待确认',
  CONFIRMED: '已完成', REVIEWED: '已评价', CANCELLED: '已取消',
  REFUNDING: '退款中', REFUNDED: '已退款', DISPUTED: '争议中', ARBITRATED: '已仲裁'
}
const orderTagTypeMap = {
  PENDING_PAYMENT: 'info', PAID: '', ASSIGNED: '', ACCEPTED: '',
  WAITING_TEAMMATE: 'warning', IN_PROGRESS: 'warning', COMPLETED: 'success',
  CONFIRMED: 'success', REVIEWED: 'success', CANCELLED: 'info',
  REFUNDING: 'danger', REFUNDED: 'info', DISPUTED: 'danger', ARBITRATED: 'info'
}
function orderStatusLabel(s) { return orderStatusMap[s] || s }
function orderTagType(s) { return orderTagTypeMap[s] || '' }
function parsedExtraFields(order) {
  if (!order?.extraFields) return []
  try {
    const obj = typeof order.extraFields === 'string' ? JSON.parse(order.extraFields) : order.extraFields
    return Object.entries(obj).map(([key, value]) => ({ key, value }))
  } catch { return [] }
}
const query = reactive({ pageNum: 1, pageSize: 10, orderNo: '', status: '', csId: null })
const csStaff = ref([])
const detailCsId = ref(0)
const { data: result, loading, error: loadError, run: loadOrders } = useLoadTask(async params => {
  const fn = isAdmin ? adminOrderList : csOrderList
  return (await fn(params, { silent: true })).data || {}
}, {})
const list = computed(() => result.value.records || [])
const total = computed(() => Number(result.value.total || 0))
function fetchData() { return loadOrders({ ...query }) }
function searchOrders() { query.pageNum = 1; return fetchData() }
function resetSearch() { query.orderNo = ''; query.status = ''; query.csId = null; searchOrders() }
// 详情
const detailVisible = ref(false), detail = ref(null), detailLoading = ref(false), progressList = ref([])
const confirmLoading = ref(false)
async function showDetail(id) {
  detailVisible.value = true; detailLoading.value = true; detail.value = null; progressList.value = []
  try {
    const detailFn = isAdmin ? adminOrderDetail : csOrderDetail
    const progressFn = isAdmin ? adminOrderProgress : csOrderProgress
    const detailRes = await detailFn(id)
    detail.value = detailRes.data
    detailCsId.value = detail.value?.csId || 0
    try { progressList.value = (await progressFn(id)).data || [] }
    catch (_) { progressList.value = []; ElMessage.warning('订单进度加载失败，可稍后重试') }
  } catch (_) {
    ElMessage.error('订单详情加载失败，请稍后重试')
  } finally { detailLoading.value = false }
}
async function refreshTeamOrder() {
  const id = detail.value?.id
  if (!id || !detailVisible.value) return
  try {
    const [fresh, timeline] = await Promise.all([
      (isAdmin ? adminOrderDetail : csOrderDetail)(id),
      (isAdmin ? adminOrderProgress : csOrderProgress)(id),
    ])
    if (detailVisible.value && detail.value?.id === id) {
      detail.value = fresh.data
      progressList.value = timeline.data || []
      fetchData()
    }
  } catch (_) { ElMessage.warning('协同操作已保存，订单详情可稍后刷新') }
}
async function loadCsStaff() {
  if (!isAdmin) return
  try { csStaff.value = (await csRoutingStaff()).data || [] } catch (_) { csStaff.value = [] }
}
async function saveCsAssignment(value) {
  if (!detail.value?.id) return
  try {
    await adminOrderReassignCs(detail.value.id, value || null)
    ElMessage.success('归属客服已更新')
    await showDetail(detail.value.id)
    fetchData()
  } catch (_) {
    detailCsId.value = detail.value?.csId || 0
  }
}
async function handleConfirm(row) {
  await ElMessageBox.confirm('确定手动结单？订单将变为已确认状态。', '结单确认', { type: 'warning' })
  confirmLoading.value = true
  try {
    const fn = isAdmin ? adminOrderConfirm : csOrderConfirm
    await fn(row.id)
    ElMessage.success('结单成功')
    await showDetail(row.id)
    fetchData()
  } finally {
    confirmLoading.value = false
  }
}
// 指派
const assignVisible = ref(false), assignOrderId = ref(null), assignMode = ref('assign')
const playerSearch = ref(''), candidateKeyword = ref(''), candidatePage = ref(1)
const assignmentCandidates = ref(null), playerLoading = ref(false), candidateError = ref('')
const assigning = ref(false), assignError = ref('')
const candidatePageSize = 20
let candidateRequest = 0
const playerList = computed(() => playerLoading.value ? [] : assignmentCandidates.value?.players?.records || [])
function canAssign(row) {
  return can('order.manage') && ((row.status === 'PAID' && !row.playerId) || row.status === 'ASSIGNED')
}
function openAssignDialog(row) {
  if (assigning.value) return
  assignOrderId.value = row.id
  assignMode.value = row.status === 'ASSIGNED' ? 'reassign' : 'assign'
  playerSearch.value = ''
  assignmentCandidates.value = null
  assignVisible.value = true
  searchPlayers()
}
function searchPlayers() {
  if (assigning.value) return
  candidateKeyword.value = playerSearch.value.trim()
  candidatePage.value = 1
  assignError.value = ''
  return fetchPlayers()
}
async function fetchPlayers() {
  if (!assignVisible.value) return
  const current = ++candidateRequest
  playerLoading.value = true
  candidateError.value = ''
  try {
    const res = await orderAssignmentCandidates(isAdmin, assignOrderId.value, {
      pageNum: candidatePage.value, pageSize: candidatePageSize, keyword: candidateKeyword.value
    })
    if (current === candidateRequest) assignmentCandidates.value = res.data
  } catch (error) {
    if (current === candidateRequest) candidateError.value = error?.response?.data?.msg || error?.message || '请稍后重试'
  } finally {
    if (current === candidateRequest) playerLoading.value = false
  }
}
function isFull(row) {
  return row.activeOrders >= assignmentCandidates.value?.maxConcurrent
}
async function handleAssign(row) {
  if (assigning.value || playerLoading.value || candidateError.value || !playerList.value.includes(row) || isFull(row)) return
  assigning.value = true
  assignError.value = ''
  const orderId = assignOrderId.value
  try {
    await (isAdmin ? adminOrderAssign : csOrderAssign)(orderId, row.id)
    ElMessage.success(assignMode.value === 'reassign' ? '重新指派成功' : '指派成功')
    assignVisible.value = false
    fetchData()
    if (detailVisible.value && detail.value?.id === orderId) showDetail(orderId)
  } catch (error) {
    assignError.value = error?.response?.data?.msg || error?.message || '指派失败，请重试'
    await fetchPlayers()
  } finally {
    assigning.value = false
  }
}
watch(assignVisible, visible => { if (!visible) ++candidateRequest })
onBeforeUnmount(() => { ++candidateRequest })
async function handleRefund(row) {
  await ElMessageBox.confirm('确定为该订单退款？订单将被取消并退回支付金额。', '退款确认', { type: 'warning' })
  const fn = isAdmin ? adminOrderRefund : csOrderRefund
  await fn(row.id)
  ElMessage.success('退款已提交')
  fetchData()
}
const route = useRoute()
onMounted(loadCsStaff)
watch(() => route.query, queryParams => {
  query.orderNo = typeof queryParams.orderNo === 'string' ? queryParams.orderNo.slice(0, 32) : ''
  query.status = queryParams.status === 'PAID' ? 'PAID' : ''
  searchOrders()
}, { immediate: true })
</script>
<style scoped>
.search-form { margin-bottom: 16px; }
.assignment-note { color: var(--el-text-color-secondary); }
.assignment-error { color: var(--el-color-danger); }
.detail-actions { margin-top: 16px; text-align: right; }
.team-audit-reason { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 6px; overflow-wrap: anywhere; }
.team-member { padding: 8px 0; border-top: 1px solid #ebeef5; }
.team-member:first-of-type { border-top: 0; }
.team-member-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.team-name { font-weight: 500; }
.team-member-meta { display: flex; align-items: center; gap: 8px 14px; flex-wrap: wrap; margin: 6px 0 0 38px; color: #909399; font-size: 12px; }
.progress-images { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.progress-image { width: 72px; height: 72px; border-radius: 6px; overflow: hidden; }
</style>
