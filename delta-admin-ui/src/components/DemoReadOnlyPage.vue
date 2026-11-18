<template>
  <section class="demo-page">
    <el-card shadow="never">
      <template #header>
        <div class="demo-line"><strong>{{ route.meta.title || '工作台' }}</strong><el-tag effect="plain" type="info">演示账号仅可查看</el-tag></div>
      </template>
      <el-skeleton v-if="loading" :rows="4" animated />
      <el-result v-else-if="error" icon="error" title="暂时无法加载">
        <template #extra><el-button @click="load">重试</el-button></template>
      </el-result>
      <template v-else>
        <div v-if="data.total != null" class="demo-line"><span>记录数</span><strong>{{ data.total }}</strong></div>
        <div v-if="data.masked" class="demo-line"><span>内部资料</span><span aria-label="已脱敏">******</span></div>
        <template v-if="data.products">
          <h3>已上架商品 · {{ data.productCount }}</h3>
          <el-table :data="data.products" empty-text="暂无商品">
            <el-table-column prop="name" label="商品名称" min-width="130" />
          </el-table>
        </template>
        <template v-if="data.orderSummary">
          <h3>订单状态汇总</h3>
          <el-table :data="data.orderSummary" empty-text="暂无订单">
            <el-table-column label="状态" min-width="120"><template #default="{ row }">{{ statuses[row.status] || '其他' }}</template></el-table-column>
            <el-table-column prop="total" label="数量" width="80" />
          </el-table>
        </template>
      </template>
    </el-card>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { canonicalAdminPath } from '@/utils/h5-route'
const route = useRoute()
const page = canonicalAdminPath(route.meta.activeMenu || route.path).replace(/\/$/, '') || '/dashboard'
const data = ref({})
const loading = ref(true)
const error = ref(false)
const statuses = { PENDING_PAYMENT: '待支付', PAID: '待接单', ASSIGNED: '已指派', ACCEPTED: '已接单', WAITING_TEAMMATE: '待组队', IN_PROGRESS: '进行中', COMPLETED: '待确认', CONFIRMED: '已完成', REVIEWED: '已评价', SETTLED: '已结算', DISPUTED: '售后中', ARBITRATED: '已仲裁', REFUNDED: '已退款', CANCELLED: '已取消' }
async function load() {
  loading.value = true; error.value = false
  try { data.value = (await request.get('/admin/demo/overview', { params: { page }, loading: false, silent: true })).data }
  catch (_) { error.value = true }
  finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.demo-line { display:flex; align-items:center; justify-content:space-between; gap:12px; flex-wrap:wrap; }
:deep(.el-card__body) > .demo-line { padding:14px 0; border-bottom:1px solid #eee; font-size:14px }
.demo-page h3 { margin:20px 0 10px; font-size:16px }
</style>
