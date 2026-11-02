<template>
  <div class="page-container">
    <el-card v-loading="loading">
      <template #header>
        <div class="header">
          <div><h3>打手列表布局</h3><p>选择客户端“全部打手”页面使用的展示方式，保存后立即生效。</p></div>
          <el-button type="primary" :loading="saving" :disabled="!selected" @click="save">保存布局</el-button>
        </div>
      </template>
      <el-alert title="此功能由功能商城授权控制。授权关闭或到期后，客户端会自动回退到默认图文卡片布局，已保存的选择会保留。" type="info" :closable="false" show-icon />
      <div class="layout-grid">
        <button v-for="option in options" :key="option.id" type="button" class="layout-option" :class="{ selected: selected === option.id }" @click="selected = option.id">
          <div class="option-heading"><div><strong>{{ option.name }}</strong><small>{{ option.id }}</small></div><el-tag v-if="selected === option.id" type="success">当前选择</el-tag></div>
          <p>{{ option.description }}</p>
          <div class="preview" :class="`preview-${option.id}`">
            <template v-if="option.id === 'grid'">
              <div v-for="n in 3" :key="n" class="grid-item"><span class="portrait"></span><i></i><em></em></div>
            </template>
            <template v-else-if="option.id === 'list'">
              <div v-for="n in 2" :key="n" class="list-item"><span class="portrait"></span><div><i></i><em></em><small></small></div><b></b></div>
            </template>
            <template v-else>
              <div v-for="n in 4" :key="n" class="compact-item"><span class="portrait"></span><div><i></i><em></em></div><b></b></div>
            </template>
          </div>
        </button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getPlayerLayoutSettings, savePlayerLayoutSettings } from '@/api/business'

const loading = ref(false)
const saving = ref(false)
const selected = ref('')
const options = ref([])

onMounted(async () => {
  loading.value = true
  try {
    const { data } = await getPlayerLayoutSettings()
    selected.value = data?.layout || 'grid'
    options.value = Array.isArray(data?.options) ? data.options : []
  } finally {
    loading.value = false
  }
})

async function save() {
  if (!selected.value || saving.value) return
  saving.value = true
  try {
    await savePlayerLayoutSettings(selected.value)
    ElMessage.success('打手列表布局已保存')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.header{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.header h3{margin:0;font-size:19px}.header p{margin:8px 0 0;color:#73777e;line-height:1.7}
.layout-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,280px),1fr));gap:18px;margin-top:20px}
.layout-option{display:block;width:100%;padding:18px;text-align:left;border:1px solid #e4e7eb;border-radius:14px;background:#fff;cursor:pointer;transition:border-color .18s ease,box-shadow .18s ease,transform .18s ease}.layout-option:hover{border-color:#b8bdc5;transform:translateY(-1px)}.layout-option.selected{border-color:#17191c;box-shadow:0 0 0 2px rgba(23,25,28,.08)}
.option-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.option-heading strong{display:block;color:#17191c;font-size:16px}.option-heading small{display:block;margin-top:4px;color:#a0a4aa;font-size:11px;letter-spacing:.08em}
.layout-option p{min-height:44px;margin:12px 0 16px;color:#666b73;font-size:13px;line-height:1.65}
.preview{height:190px;padding:12px;overflow:hidden;border:1px solid #ebeef1;border-radius:12px;background:#f6f7f9}
.portrait{display:block;background:#d9dde2;border-radius:8px}
.preview-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;align-content:start}.grid-item{padding:0 0 8px;border-radius:9px;background:#fff;overflow:hidden}.grid-item .portrait{height:72px;border-radius:0}.grid-item i,.grid-item em{display:block;height:6px;margin:7px 7px 0;border-radius:999px;background:#d9dde2}.grid-item em{width:55%;background:#eceef1}
.preview-list{display:grid;gap:10px}.list-item{display:grid;grid-template-columns:64px 1fr auto;align-items:center;gap:10px;padding:10px;border-radius:10px;background:#fff}.list-item .portrait{width:64px;height:64px}.list-item i,.list-item em,.list-item small{display:block;height:6px;border-radius:999px;background:#d9dde2}.list-item i{width:58%}.list-item em{width:82%;margin-top:8px;background:#eceef1}.list-item small{width:45%;margin-top:8px;background:#eef0f2}.list-item b,.compact-item b{display:block;width:42px;height:22px;border-radius:999px;background:#17191c}
.preview-compact{display:grid;gap:7px}.compact-item{display:grid;grid-template-columns:42px 1fr auto;align-items:center;gap:8px;padding:7px 9px;border-radius:8px;background:#fff}.compact-item .portrait{width:42px;height:42px}.compact-item i,.compact-item em{display:block;height:5px;border-radius:999px;background:#d9dde2}.compact-item i{width:42%}.compact-item em{width:72%;margin-top:6px;background:#eceef1}.compact-item b{width:30px;height:16px}
@media(max-width:700px){.header{flex-direction:column}.header :deep(.el-button){align-self:stretch}}
</style>
