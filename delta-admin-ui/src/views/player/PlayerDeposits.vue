<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="deposit-header">
          <span>押金记录</span>
          <span class="deposit-hint">支付中、已关闭和退款记录也保留，便于核对到账。</span>
        </div>
      </template>
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item>
          <el-input v-model="query.keyword" placeholder="支付单号 / 打手昵称 / ID" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.status" placeholder="押金状态" clearable style="width: 150px">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="打手" min-width="150">
          <template #default="{ row }">
            <div class="player-cell">{{ row.playerName || '未绑定打手' }}</div>
            <div class="player-sub">ID: {{ row.playerId || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="levelName" label="等级" width="110"><template #default="{ row }">{{ row.levelName || '-' }}</template></el-table-column>
        <el-table-column prop="paymentNo" label="支付单号" min-width="190" show-overflow-tooltip />
        <el-table-column prop="amount" label="金额" width="110"><template #default="{ row }">¥{{ money(row.amount) }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }"><el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="payMethod" label="支付方式" width="140" />
        <el-table-column prop="paidAt" label="到账时间" width="170" />
        <el-table-column prop="refundReason" label="退款说明" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!row.playerId" @click="openPlayer(row)">查看打手</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!loading && !list.length" class="empty-state">暂无押金支付记录</div>
      <Pagination :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="fetchData" />
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminPlayerDepositList } from '@/api/business'
import Pagination from '@/components/Pagination.vue'

const router = useRouter()
const route = useRoute()
const list = ref([]), total = ref(0), loading = ref(false)
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '' })
if (route.query.keyword) query.keyword = String(route.query.keyword)
const statusOptions = [
  { value: 'PAID', label: '已到账' },
  { value: 'PAYING', label: '支付中' },
  { value: 'CLOSED', label: '已关闭' },
  { value: 'REFUNDING', label: '退款中' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'FAILED', label: '支付失败' }
]
const statusMap = { PAID: '已到账', PAYING: '支付中', CLOSED: '已关闭', REFUNDING: '退款中', REFUNDED: '已退款', FAILED: '支付失败' }
const statusTagMap = { PAID: 'success', PAYING: 'warning', CLOSED: 'info', REFUNDING: 'warning', REFUNDED: 'danger', FAILED: 'danger' }
function statusText(value) { return statusMap[value] || value || '-' }
function statusTag(value) { return statusTagMap[value] || 'info' }
function money(value) { return Number(value || 0).toFixed(2) }
async function fetchData() {
  loading.value = true
  try {
    const result = await adminPlayerDepositList(query)
    list.value = result.data?.records || []
    total.value = Number(result.data?.total || 0)
  } finally {
    loading.value = false
  }
}
function search() { query.pageNum = 1; fetchData() }
function reset() { query.keyword = ''; query.status = ''; query.pageNum = 1; fetchData() }
function openPlayer(row) {
  router.push({ path: '/player', query: { taskId: row.playerId } })
}
fetchData()
</script>

<style scoped>
.deposit-header { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.deposit-hint { color: #667085; font-size: 12px; font-weight: 400; }
.player-cell { color: #17191d; font-weight: 500; }
.player-sub { margin-top: 2px; color: #98a2b3; font-size: 12px; }
.empty-state { padding: 28px 0; text-align: center; color: #98a2b3; font-size: 13px; }
</style>
