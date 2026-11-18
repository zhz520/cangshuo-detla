<template>
  <div :class="{ 'page-container': !embedded }">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <strong>{{ embedded ? '会员规则' : '会员等级' }}</strong>
            <p class="subtitle">按有效净消费自动升级。最低等级门槛必须为 0 元，等级门槛不能重复；等级越高折扣不能变差，折扣率 0.95 表示 95 折。</p>
          </div>
          <div class="header-actions">
            <el-switch v-model="enabled" active-text="启用会员权益" inactive-text="关闭" />
            <el-button v-if="!embedded" type="primary" :loading="saving" :disabled="loading || (embedded && !dirty)" @click="save">保存配置</el-button>
          </div>
        </div>
      </template>
      <el-alert title="关闭后停止新增会员等级和折扣权益，既有会员等级与订单优惠快照保留。" type="info" :closable="false" show-icon />
      <el-table :data="rows" v-loading="loading" stripe class="tier-table">
        <el-table-column label="等级编码" min-width="150">
          <template #default="{ row }"><el-input v-model="row.code" maxlength="32" placeholder="如 SILVER" @input="row.code = String(row.code || '').toUpperCase()" /></template>
        </el-table-column>
        <el-table-column label="等级名称" min-width="150">
          <template #default="{ row }"><el-input v-model="row.name" maxlength="30" placeholder="如 白银会员" /></template>
        </el-table-column>
        <el-table-column label="累计净消费门槛（元）" min-width="180">
          <template #default="{ row }"><el-input-number v-model="row.minSpend" :min="0" :max="100000000" :precision="2" :step="100" style="width:100%" /></template>
        </el-table-column>
        <el-table-column label="折扣率" min-width="170">
          <template #default="{ row }">
            <el-input-number v-model="row.discountRate" :min="0.01" :max="1" :precision="4" :step="0.01" style="width:100%" />
            <span class="rate-hint">{{ rateText(row.discountRate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ $index }"><el-button link type="danger" :disabled="rows.length <= 1" @click="rows.splice($index, 1)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <el-button class="add-button" @click="addTier">新增等级</el-button>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getMembershipConfig, saveMembershipConfig } from '@/api/marketing'

const props = defineProps({ embedded: Boolean })
const emit = defineEmits(['dirty', 'saved', 'busy'])
const loading = ref(false)
const saving = ref(false)
const enabled = ref(false)
const rows = ref([])
const baseline = ref(null)
const snapshot = () => JSON.stringify([enabled.value, rows.value])
const dirty = computed(() => baseline.value !== null && snapshot() !== baseline.value)
watch(dirty, value => emit('dirty', value), { immediate: true })
watch(saving, value => emit('busy', value), { immediate:true })

const rateText = value => {
  const rate = Number(value || 1)
  return rate >= 1 ? '无折扣' : `${(rate * 10).toFixed(2).replace(/0+$/, '').replace(/\.$/, '')} 折`
}

async function load() {
  loading.value = true
  try {
    const { data } = await getMembershipConfig()
    enabled.value = data?.enabled === true
    rows.value = (Array.isArray(data?.tiers) ? data.tiers : []).map(item => ({
      code: item.code || '', name: item.name || '', minSpend: Number(item.minSpend || 0), discountRate: Number(item.discountRate || 1)
    }))
    if (!rows.value.length) addTier()
    baseline.value = snapshot()
  } finally {
    loading.value = false
  }
}

function addTier() {
  const last = rows.value[rows.value.length - 1]
  rows.value.push({ code: `TIER_${rows.value.length + 1}`, name: `等级 ${rows.value.length + 1}`, minSpend: last ? Number(last.minSpend || 0) + 500 : 0, discountRate: last ? Number(last.discountRate || 1) : 1 })
}

function discard() {
  if (baseline.value === null || saving.value) return
  const [previousEnabled, previousRows] = JSON.parse(baseline.value)
  enabled.value = previousEnabled
  rows.value = previousRows
}
defineExpose({ save, discard })

async function save() {
  if (saving.value || loading.value || (props.embedded && !dirty.value)) return
  const tiers = [...rows.value].map(item => ({
    code: String(item.code || '').trim().toUpperCase(), name: String(item.name || '').trim(),
    minSpend: Number(item.minSpend || 0), discountRate: Number(item.discountRate || 1)
  })).sort((a, b) => a.minSpend - b.minSpend)
  if (!tiers.length) return ElMessage.warning('至少保留一个会员等级')
  saving.value = true
  try {
    const { data } = await saveMembershipConfig({ enabled: enabled.value, tiers })
    enabled.value = data?.enabled === true
    rows.value = (Array.isArray(data?.tiers) ? data.tiers : tiers).map(item => ({
      code: item.code || '', name: item.name || '', minSpend: Number(item.minSpend || 0), discountRate: Number(item.discountRate || 1)
    }))
    baseline.value = snapshot()
    emit('saved')
    ElMessage.success('会员等级已保存')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.card-header { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; flex-wrap:wrap; }
.subtitle { margin:8px 0 0; color:#6b7280; font-size:13px; line-height:1.6; }
.header-actions { display:flex; align-items:center; gap:16px; flex-wrap:wrap; }
.tier-table { margin-top:18px; }
.rate-hint { display:block; margin-top:4px; color:#909399; font-size:12px; }
.add-button { margin-top:16px; }
</style>
