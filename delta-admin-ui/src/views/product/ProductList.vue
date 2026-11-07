<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>商品管理</span>
            <p class="header-hint">支持勾选商品后批量上下架、调分类、调价、复制和删除</p>
          </div>
          <el-button type="primary" @click="$router.push('/product/create')">新增商品</el-button>
        </div>
      </template>
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item><el-input v-model="query.keyword" placeholder="商品名称" clearable @keyup.enter="fetchData" /></el-form-item>
        <el-form-item><el-select v-model="query.categoryId" placeholder="分类" clearable><el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" /></el-select></el-form-item>
        <el-form-item><el-select v-model="query.status" placeholder="状态" clearable><el-option label="上架" :value="1" /><el-option label="下架" :value="0" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" @click="fetchData">查询</el-button><el-button @click="resetQuery">重置</el-button></el-form-item>
      </el-form>

      <BatchActionBar :count="selectedRows.length" :disabled="batchBusy" @clear="clearSelection">
        <el-button link :disabled="batchBusy" @click="batchStatus(1)">批量上架</el-button>
        <el-button link :disabled="batchBusy" @click="batchStatus(0)">批量下架</el-button>
        <el-button link :disabled="batchBusy" @click="categoryDialog = true">调整分类</el-button>
        <el-button link :disabled="batchBusy" @click="priceDialog = true">调整价格</el-button>
        <el-button link :disabled="batchBusy" @click="batchCopy">复制为草稿</el-button>
        <el-button link type="danger" :disabled="batchBusy" @click="batchDelete">批量删除</el-button>
      </BatchActionBar>

      <el-table ref="tableRef" :data="list" v-loading="loading" stripe row-key="id" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="48" :selectable="row => !batchBusy && row.id > 0" />
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="图片" width="80"><template #default="{ row }"><el-image lazy v-if="row.coverImage" :src="thumbnailUrl(row.coverImage,160)" style="width:50px;height:50px;border-radius:8px" fit="cover" :preview-src-list="[row.coverImage]" preview-teleported /></template></el-table-column>
        <el-table-column prop="name" label="商品名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="价格" width="120"><template #default="{ row }">{{ row.price != null ? '¥' + Number(row.price).toFixed(2) : '-' }}</template></el-table-column>
        <el-table-column label="分类" width="120"><template #default="{ row }">{{ getCategoryName(row.categoryId) }}</template></el-table-column>
        <el-table-column prop="salesCount" label="销量" width="80" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-switch :model-value="row.status === 1" :disabled="batchBusy" @change="handleToggleStatus(row)" active-text="上架" inactive-text="下架" inline-prompt /></template></el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="150" :fixed="isCompact ? false : 'right'">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/product/edit/${row.id}`)">编辑</el-button>
            <el-popconfirm title="确认删除这个商品？" @confirm="handleDelete(row.id)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="fetchData" />
    </el-card>

    <el-dialog v-model="categoryDialog" title="批量调整分类" width="min(460px, 94vw)">
      <p class="dialog-tip">将已选的 {{ selectedRows.length }} 个商品统一移动到：</p>
      <el-select v-model="batchCategoryId" placeholder="请选择商品分类" filterable style="width:100%">
        <el-option v-for="c in categories.filter(item => item.status !== 0)" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <template #footer><el-button @click="categoryDialog = false">取消</el-button><el-button type="primary" :disabled="!batchCategoryId || batchBusy" :loading="batchBusy" @click="applyBatchCategory">确认修改</el-button></template>
    </el-dialog>

    <el-dialog v-model="priceDialog" title="批量调整价格" width="min(500px, 94vw)">
      <p class="dialog-tip">将已选的 {{ selectedRows.length }} 个商品统一处理，批量复制不会影响原商品。</p>
      <el-radio-group v-model="priceMode" class="price-mode">
        <el-radio-button value="PERCENT">按比例调整</el-radio-button>
        <el-radio-button value="SET">统一设置价格</el-radio-button>
      </el-radio-group>
      <el-form-item :label="priceMode === 'SET' ? '新价格（元）' : '调整比例（%）'">
        <el-input-number v-model="priceValue" :min="priceMode === 'SET' ? 0 : -100" :max="priceMode === 'SET' ? 1000000 : 1000" :precision="2" style="width:100%" />
      </el-form-item>
      <p class="dialog-tip">{{ priceMode === 'SET' ? '所有选中商品设置为该价格' : priceValue === 0 ? '比例为零时不会改变价格' : `在当前价格基础上${priceValue > 0 ? '上调' : '下调'} ${Math.abs(priceValue)}%` }}</p>
      <template #footer><el-button @click="priceDialog = false">取消</el-button><el-button type="primary" :loading="batchBusy" :disabled="batchBusy" @click="applyBatchPrice">确认调整</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { batchCopyProducts, batchDeleteProducts, batchProductCategory, batchProductPrice, batchProductStatus, deleteProduct, getAllCategories, getProductList, updateProductStatus } from '@/api/product'
import { showBatchResult } from '@/utils/batch-result'
import { useResponsive } from '@/utils/responsive'
import Pagination from '@/components/Pagination.vue'
import BatchActionBar from '@/components/BatchActionBar.vue'
import { thumbnailUrl } from '@/utils/image'

const { isCompact } = useResponsive()
const loading = ref(false), list = ref([]), total = ref(0), categories = ref([])
const tableRef = ref(null), selectedRows = ref([]), batchBusy = ref(false)
const selectedIds = computed(() => selectedRows.value.map(row => row.id))
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', categoryId: '', status: '' })
const categoryDialog = ref(false), batchCategoryId = ref('')
const priceDialog = ref(false), priceMode = ref('PERCENT'), priceValue = ref(10)

function getCategoryName(cid) { const c = categories.value.find(i => i.id === cid); return c ? c.name : '-' }
function onSelectionChange(rows) { selectedRows.value = rows }
function clearSelection() { tableRef.value?.clearSelection(); selectedRows.value = [] }
function resetQuery() { query.keyword = ''; query.categoryId = ''; query.status = ''; query.pageNum = 1; fetchData() }
async function fetchData() {
  clearSelection()
  loading.value = true
  try {
    const res = await getProductList(query)
    list.value = res.data.records
    total.value = Number(res.data.total)
  } finally { loading.value = false }
}
async function handleToggleStatus(row) {
  const newStatus = row.status === 1 ? 0 : 1
  await updateProductStatus(row.id, newStatus)
  ElMessage.success(newStatus === 1 ? '已上架' : '已下架')
  fetchData()
}
async function handleDelete(id) {
  try {
    await ElMessageBox.confirm('确认删除这个商品？历史订单和评价会保留。', '删除商品', { type: 'warning' })
    await deleteProduct(id)
    ElMessage.success('删除成功')
    fetchData()
    clearSelection()
  } catch (_) { /* cancelled or request interceptor already showed the error */ }
}
async function runBatch(action, label) {
  if (!selectedIds.value.length || batchBusy.value) return
  batchBusy.value = true
  try {
    const response = await action(selectedIds.value)
    showBatchResult(response.data, label)
    clearSelection()
    await fetchData()
  } catch (_) { /* request interceptor displays the server error */ }
  finally { batchBusy.value = false }
}
async function confirmBatch(message, title) {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认处理' })
    return true
  } catch (_) { return false }
}
async function batchStatus(status) {
  if (!await confirmBatch(`确认将已选的 ${selectedRows.value.length} 个商品${status === 1 ? '上架' : '下架'}？`, status === 1 ? '批量上架' : '批量下架')) return
  await runBatch(ids => batchProductStatus(ids, status), status === 1 ? '批量上架' : '批量下架')
}
async function batchCopy() {
  if (!await confirmBatch(`将已选的 ${selectedRows.value.length} 个商品复制为下架草稿？名称会追加“（副本）”。`, '批量复制商品')) return
  await runBatch(ids => batchCopyProducts(ids), '批量复制')
}
async function batchDelete() {
  if (!await confirmBatch(`确认删除已选的 ${selectedRows.value.length} 个商品？此操作会从商品列表移除，历史订单保留。`, '批量删除商品')) return
  await runBatch(ids => batchDeleteProducts(ids), '批量删除')
}
async function applyBatchCategory() {
  if (!batchCategoryId.value || !await confirmBatch(`将 ${selectedRows.value.length} 个商品统一调整到“${getCategoryName(batchCategoryId.value)}”？`, '批量调整分类')) return
  await runBatch(ids => batchProductCategory(ids, batchCategoryId.value), '批量调整分类')
  categoryDialog.value = false
}
async function applyBatchPrice() {
  if (!await confirmBatch(`确认调整已选的 ${selectedRows.value.length} 个商品价格？`, '批量调整价格')) return
  await runBatch(ids => batchProductPrice(ids, priceMode.value, priceValue.value), '批量调整价格')
  priceDialog.value = false
}
onMounted(async () => {
  fetchData()
  try { categories.value = (await getAllCategories()).data || [] } catch (_) {}
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.card-header > div { min-width: 0; }
.card-header > div > span { font-weight: 700; }
.header-hint { margin: 5px 0 0; color: #8a8f98; font-size: 12px; }
.search-form { margin-bottom: 16px; }
.dialog-tip { color: #73777e; font-size: 13px; line-height: 1.7; }
.price-mode { margin: 8px 0 18px; }
@media (max-width: 760px) {
  .card-header { align-items: flex-start; flex-direction: column; }
  .card-header > .el-button { width: 100%; }
}
</style>
