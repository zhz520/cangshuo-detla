<template>
  <section class="flash-sale-page">
    <PageHeader title="秒杀活动" description="独立名额、固定价、每账号 1 单；不叠加优惠券、会员折扣或指定打手等级。">
      <template #actions><el-button type="primary" @click="open()">新增活动</el-button></template>
    </PageHeader>
    <el-alert type="info" :closable="false" show-icon title="活动只影响当前租户；库存扣减和订单创建在同一 MySQL 事务内完成，未支付取消后由定时任务返还名额。" />
    <DataTable :loading="busy" :data="rows" row-key="id" empty-text="还没有秒杀活动">
      <el-table-column prop="name" label="活动名称" min-width="170" />
      <el-table-column prop="productName" label="商品" min-width="160" />
      <el-table-column label="秒杀价" width="120"><template #default="{ row }">¥{{ Number(row.price).toFixed(2) }}</template></el-table-column>
      <el-table-column label="库存" width="110"><template #default="{ row }">{{ row.remainingStock }} / {{ row.totalStock }}</template></el-table-column>
      <el-table-column label="时间" min-width="250"><template #default="{ row }">{{ short(row.startsAt) }} 至 {{ short(row.endsAt) }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled?'success':'info'" size="small">{{ row.enabled?'启用':'停用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
    </DataTable>

    <el-dialog v-model="dialog" :title="form.id?'编辑秒杀活动':'新增秒杀活动'" width="min(620px,94vw)" destroy-on-close>
      <el-form label-position="top" class="form">
        <el-form-item label="活动名称"><el-input v-model="form.name" maxlength="80" /></el-form-item>
        <el-form-item label="商品"><el-select v-model="form.productId" filterable style="width:100%"><el-option v-for="item in products" :key="item.id" :label="`${item.name} · ¥${Number(item.price).toFixed(2)}`" :value="item.id" /></el-select></el-form-item>
        <div class="grid">
          <el-form-item label="秒杀价（元）"><el-input-number v-model="form.price" :min="0.01" :precision="2" :step="1" /></el-form-item>
          <el-form-item label="独立库存"><el-input-number v-model="form.stock" :min="1" :max="1000000" /></el-form-item>
        </div>
        <div class="grid">
          <el-form-item label="开始时间"><el-date-picker v-model="form.startsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" format="YYYY-MM-DD HH:mm" /></el-form-item>
          <el-form-item label="结束时间"><el-date-picker v-model="form.endsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" format="YYYY-MM-DD HH:mm" /></el-form-item>
        </div>
        <el-checkbox v-model="form.enabled">启用活动</el-checkbox>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import DataTable from '@/components/DataTable.vue'
import { getProductList } from '@/api/product'
import { deleteFlashSale, getFlashSales, saveFlashSale } from '@/api/flash-sale'

const rows = ref([]), products = ref([]), busy = ref(false), saving = ref(false), dialog = ref(false)
const form = reactive({ id: null, name: '', productId: null, price: 1, stock: 10, startsAt: '', endsAt: '', enabled: false })
function short(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
async function load() { busy.value = true; try { rows.value = (await getFlashSales()).data || [] } catch (error) { ElMessage.error(error?.message || '秒杀活动读取失败') } finally { busy.value = false } }
function open(row = null) { Object.assign(form, { id: null, name: '', productId: null, price: 1, stock: 10, startsAt: '', endsAt: '', enabled: false }, row ? { id: row.id, name: row.name, productId: row.productId, price: Number(row.price), stock: row.totalStock, startsAt: String(row.startsAt || '').replace(' ', 'T').slice(0, 19), endsAt: String(row.endsAt || '').replace(' ', 'T').slice(0, 19), enabled: row.enabled } : {}); dialog.value = true }
async function save() { saving.value = true; try { await saveFlashSale(form.id, form); dialog.value = false; ElMessage.success('秒杀活动已保存'); await load() } catch (error) { ElMessage.error(error?.message || '保存失败') } finally { saving.value = false } }
async function remove(row) { try { await ElMessageBox.confirm(`确认删除“${row.name}”？已有参与记录时只能停用。`, '删除秒杀活动', { type: 'warning' }) } catch { return } try { await deleteFlashSale(row.id); ElMessage.success('活动已删除'); await load() } catch (error) { ElMessage.error(error?.message || '删除失败') } }
onMounted(async () => { try { const result = await getProductList({ page: 1, size: 500 }); products.value = result.data?.records || result.data || [] } catch {} await load() })
</script>

<style scoped>
.flash-sale-page { display:grid; gap:16px; }.form { display:grid; gap:2px; }.grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; } @media(max-width:768px){.grid{grid-template-columns:1fr}}
</style>
