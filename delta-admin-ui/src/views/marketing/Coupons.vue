<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <strong>优惠券模板</strong>
            <p class="subtitle">发放与核销总开关在“系统管理 → 系统配置 → 营销策略”。关闭发放后现有有效券仍可使用；紧急停用核销会阻止新订单用券。</p>
          </div>
          <el-button type="primary" @click="openCreate">新建优惠券</el-button>
        </div>
      </template>
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ row.type === 'DISCOUNT' ? '折扣券' : '满减券' }}</template>
        </el-table-column>
        <el-table-column label="优惠值" width="110">
          <template #default="{ row }">{{ row.type === 'DISCOUNT' ? `${Number(row.coupon_value) * 10} 折` : `¥${Number(row.coupon_value).toFixed(2)}` }}</template>
        </el-table-column>
        <el-table-column label="使用门槛" width="120"><template #default="{ row }">满 ¥{{ Number(row.min_spend || 0).toFixed(2) }}</template></el-table-column>
        <el-table-column label="有效期" width="100"><template #default="{ row }">{{ row.valid_days }} 天</template></el-table-column>
        <el-table-column label="库存" width="130"><template #default="{ row }">{{ row.total ? `${row.claimed}/${row.total}` : `${row.claimed}/不限` }}</template></el-table-column>
        <el-table-column label="每人限领" width="100" prop="per_user_limit" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="records-card">
      <template #header><div class="card-header"><strong>最近领取与核销</strong><el-button :loading="recordsLoading" @click="loadRecords">刷新</el-button></div></template>
      <el-table :data="records" v-loading="recordsLoading" stripe>
        <el-table-column label="用户" min-width="120"><template #default="{ row }">{{ row.nickname || '-' }}</template></el-table-column>
        <el-table-column label="手机号" width="130"><template #default="{ row }">{{ maskPhone(row.phone) }}</template></el-table-column>
        <el-table-column prop="coupon_name" label="优惠券" min-width="150" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="recordTag(row.status)" size="small">{{ recordText(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="领取时间" min-width="170"><template #default="{ row }">{{ formatTime(row.claimed_at) }}</template></el-table-column>
        <el-table-column label="有效期至" min-width="170"><template #default="{ row }">{{ formatTime(row.expires_at) }}</template></el-table-column>
        <el-table-column label="使用订单" width="110"><template #default="{ row }">{{ row.used_order_id || '-' }}</template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑优惠券' : '新建优惠券'" width="min(92vw, 520px)" destroy-on-close>
      <el-alert v-if="termsLocked" class="terms-lock" type="warning" :closable="false" show-icon title="已有用户领取。为防止改动已发优惠券的价值，类型、面额和使用门槛不可修改；可调整名称、状态、总量或新建模板。" />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" label-position="top">
        <el-form-item label="名称" prop="name"><el-input v-model="form.name" maxlength="80" show-word-limit /></el-form-item>
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio-button value="AMOUNT" :disabled="termsLocked">满减券</el-radio-button>
            <el-radio-button value="DISCOUNT" :disabled="termsLocked">折扣券</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="form.type === 'DISCOUNT' ? '折扣率（0.9 表示 9 折）' : '优惠金额（元）'" prop="value">
          <el-input-number v-model="form.value" :disabled="termsLocked" :min="0.01" :max="form.type === 'DISCOUNT' ? 0.99 : 100000" :precision="2" :step="form.type === 'DISCOUNT' ? 0.01 : 1" style="width:100%" />
        </el-form-item>
        <el-form-item label="最低消费（元）" prop="minSpend"><el-input-number v-model="form.minSpend" :disabled="termsLocked" :min="0" :max="1000000" :precision="2" style="width:100%" /></el-form-item>
        <el-form-item label="领取后有效天数" prop="validDays"><el-input-number v-model="form.validDays" :min="1" :max="3650" style="width:100%" /></el-form-item>
        <el-form-item label="发放总量（0 为不限）" prop="total"><el-input-number v-model="form.total" :min="0" :max="1000000" style="width:100%" /></el-form-item>
        <el-form-item label="每人限领" prop="perUserLimit"><el-input-number v-model="form.perUserLimit" :min="1" :max="100" style="width:100%" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createCouponTemplate, getCouponRecords, getCouponTemplates, updateCouponTemplate } from '@/api/marketing'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const records = ref([])
const recordsLoading = ref(false)
const dialogVisible = ref(false)
const editingId = ref(null)
const termsLocked = ref(false)
const formRef = ref()
const form = reactive({ name:'', type:'AMOUNT', value:10, minSpend:0, validDays:7, total:0, perUserLimit:1, enabled:true })
const rules = {
  name: [{ required: true, message: '请输入优惠券名称', trigger: 'blur' }],
  value: [{ required: true, message: '请输入优惠值', trigger: 'change' }],
  validDays: [{ required: true, message: '请输入有效期', trigger: 'change' }],
  perUserLimit: [{ required: true, message: '请输入每人限领数量', trigger: 'change' }]
}

async function load() {
  loading.value = true
  try {
    const { data } = await getCouponTemplates()
    rows.value = Array.isArray(data) ? data : []
  } catch { /* Shared request feedback handles failed loads. */ }
  finally { loading.value = false }
}

async function loadRecords() {
  recordsLoading.value = true
  try {
    const { data } = await getCouponRecords()
    records.value = Array.isArray(data) ? data : []
  } catch { /* Shared request feedback handles failed loads. */ }
  finally { recordsLoading.value = false }
}

const recordText = status => ({ AVAILABLE:'可用', LOCKED:'锁定', USED:'已使用', EXPIRED:'已过期' })[status] || status
const recordTag = status => status === 'USED' ? 'success' : status === 'AVAILABLE' ? 'warning' : 'info'
const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12:false }) : '-'
const maskPhone = value => value && String(value).length >= 7 ? String(value).slice(0,3) + '****' + String(value).slice(-4) : (value || '-')

function resetForm() {
  Object.assign(form, { name:'', type:'AMOUNT', value:10, minSpend:0, validDays:7, total:0, perUserLimit:1, enabled:true })
}
function openCreate() { editingId.value = null; termsLocked.value = false; resetForm(); dialogVisible.value = true }
function openEdit(row) {
  editingId.value = row.id
  termsLocked.value = Number(row.claimed || 0) > 0
  Object.assign(form, {
    name: row.name, type: row.type, value: Number(row.coupon_value), minSpend: Number(row.min_spend || 0),
    validDays: Number(row.valid_days), total: Number(row.total || 0), perUserLimit: Number(row.per_user_limit || 1), enabled: Number(row.status) === 1
  })
  dialogVisible.value = true
}
async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const payload = { ...form, status: form.enabled ? 1 : 0 }
    if (editingId.value) await updateCouponTemplate(editingId.value, payload)
    else await createCouponTemplate(payload)
    ElMessage.success('优惠券已保存')
    dialogVisible.value = false
    await load()
  } finally { saving.value = false }
}
onMounted(() => { load(); loadRecords() })
</script>

<style scoped>
.card-header { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; }
.records-card { margin-top: 18px; }
.terms-lock { margin-bottom: 16px; }
.subtitle { margin:8px 0 0; color:#6b7280; font-size:13px; line-height:1.6; }
@media (max-width: 768px) { .card-header { flex-direction:column; } }
</style>
