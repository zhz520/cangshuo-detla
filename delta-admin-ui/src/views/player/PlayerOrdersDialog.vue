<template>
  <el-dialog v-model="visible" @closed="$emit('closed')" :title="(player.nickname || 'ID: ' + player.id) + ' 的订单'" width="800px" destroy-on-close>
    <LoadFeedback :error="error" label="打手订单" @retry="refresh" />
    <el-table :data="data.records" :aria-busy="loading" v-loading="busyVisible && !data.records.length" stripe size="small">
      <template #empty>{{ error || loading ? '—' : '暂无订单' }}</template>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="orderNo" label="订单号" width="180" />
      <el-table-column prop="productName" label="商品" min-width="140" show-overflow-tooltip />
      <el-table-column prop="amount" label="金额" width="90">
        <template #default="{ row }">¥{{ row.amount }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="orderTagType(row.status)" size="small">{{ orderStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="下单时间" width="170" />
    </el-table>
    <span v-if="busyVisible && data.records.length" role="status">正在刷新订单…</span>
    <Pagination :total="data.total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="refresh" />
  </el-dialog>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import Pagination from '@/components/Pagination.vue'
import LoadFeedback from '@/components/LoadFeedback.vue'
import { useDelayedBusy } from '@/composables/useDelayedBusy'
import { useLoadTask } from '@/composables/useLoadTask'
import { adminOrderList, csOrderList } from '@/api/business'
import { readPlayerOrders } from './player-orders'

const props = defineProps({ player: { type: Object, required: true }, isAdmin: Boolean })
defineEmits(['closed'])
const visible = ref(true)
const query = reactive({ pageNum: 1, pageSize: 10 })
const { data, loading, error, run: refresh } = useLoadTask(
  () => readPlayerOrders(props.player.id, query, props.isAdmin ? adminOrderList : csOrderList),
  { records: [], total: 0 }
)
const busyVisible = useDelayedBusy(loading)
onMounted(refresh)

const orderStatusMap = {
  PENDING_PAYMENT: '待支付', PAID: '待接单', ASSIGNED: '已指派', ACCEPTED: '已接单',
  WAITING_TEAMMATE: '组队中', IN_PROGRESS: '进行中', COMPLETED: '待确认',
  CONFIRMED: '已完成', REVIEWED: '已评价', CANCELLED: '已取消',
  REFUNDING: '退款中', REFUNDED: '已退款', DISPUTED: '争议中', ARBITRATED: '已仲裁'
}
const orderTagTypeMap = {
  PENDING_PAYMENT: 'info', PAID: '', ASSIGNED: '', ACCEPTED: '',
  WAITING_TEAMMATE: '', IN_PROGRESS: 'warning', COMPLETED: 'success',
  CONFIRMED: 'success', REVIEWED: 'success', CANCELLED: 'info',
  REFUNDING: 'danger', REFUNDED: 'info', DISPUTED: 'danger', ARBITRATED: 'info'
}
function orderStatusLabel(s) { return orderStatusMap[s] || s }
function orderTagType(s) { return orderTagTypeMap[s] || '' }
</script>
