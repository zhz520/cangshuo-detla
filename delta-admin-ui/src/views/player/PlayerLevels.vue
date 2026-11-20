<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <PageHeader title="打手等级分类">
          <template #actions><el-button type="primary" @click="edit()">新增分类</el-button></template>
        </PageHeader>
      </template>
      <p class="tip">分类名称可自定义，排序值越小越靠前。停用后，该分类及其打手会从公开目录隐藏。</p>
      <AsyncState :empty="!loading && !list.length" empty-title="暂无等级分类" empty-description="新增分类后，打手可按等级归类。" @retry="load">
        <DataTable :loading="loading" :data="list" row-key="id">
          <el-table-column prop="name" label="分类名称" min-width="180" />
          <el-table-column label="涨价比例" width="120"><template #default="{row}">{{ Number(row.priceIncreaseRate || 0) }}%</template></el-table-column>
          <el-table-column label="提现押金" width="120"><template #default="{row}">{{ Number(row.withdrawDepositAmount || 0) ? '¥'+Number(row.withdrawDepositAmount).toFixed(2) : '无需押金' }}</template></el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="100" />
          <el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="row.status===1?'success':'info'">{{ row.status===1?'启用':'停用' }}</el-tag></template></el-table-column>
          <el-table-column label="抢单图形验证码" width="160"><template #default="{row}">{{ row.grabCaptchaRequired===0?'免验证':'需要验证' }}</template></el-table-column>
          <el-table-column label="操作" width="200"><template #default="{row}"><el-button link type="primary" @click="edit(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
        </DataTable>
      </AsyncState>
    </el-card>
    <el-dialog v-model="visible" :title="form.id?'编辑等级分类':'新增等级分类'" width="460px">
      <el-form label-width="90px">
        <FormSection title="基础信息" description="名称和排序决定该等级在公开目录中的展示顺序。">
          <el-form-item label="名称"><el-input v-model="form.name" maxlength="30" placeholder="如金牌打手、魔王护、高级打手" /></el-form-item>
          <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="99999" :precision="0" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
        </FormSection>
        <FormSection title="计价与规则" description="涨价、提现押金和抢单验证仅作用于该等级打手。">
          <el-form-item label="涨价比例">
            <el-input-number v-model="form.priceIncreaseRate" :min="0" :max="100" :precision="2" /><span class="unit">%</span>
            <div class="tip-inline">商品100元，涨价10%后为110元；0表示不涨价。</div>
          </el-form-item>
          <el-form-item label="提现押金">
            <el-input-number v-model="form.withdrawDepositAmount" :min="0" :max="1000000" :precision="2" /><span class="unit">元</span>
            <div class="tip-inline">仅在“注册免押金、提现前补交”模式下生效；0表示该等级提现也无需押金。</div>
          </el-form-item>
          <el-form-item label="抢单验证">
            <el-radio-group v-model="form.grabCaptchaRequired">
              <el-radio :value="0">免验证</el-radio>
              <el-radio :value="1">需要图形验证码</el-radio>
            </el-radio-group>
            <div class="tip-inline">免验证后，该等级打手接单不再弹出图形验证码；打手管理里的个人设置优先于等级设置。</div>
          </el-form-item>
        </FormSection>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { toast, confirmAction } from '@/utils/feedback'
import { playerLevelList, playerLevelCreate, playerLevelUpdate, playerLevelDelete } from '@/api/business'
import PageHeader from '@/components/PageHeader.vue'
import FormSection from '@/components/FormSection.vue'
import AsyncState from '@/components/AsyncState.vue'
import DataTable from '@/components/DataTable.vue'
const list = ref([]), loading = ref(false), visible = ref(false), saving = ref(false), original = ref(null)
const form = reactive({ id:null, name:'', sortOrder:10, status:1, grabCaptchaRequired:1, priceIncreaseRate:0, withdrawDepositAmount:0 })
onMounted(load)
async function load() { loading.value=true; try { list.value=(await playerLevelList()).data||[] } finally { loading.value=false } }
function edit(row) {
  original.value = row ? { ...row } : null
  Object.assign(form, row ? { id:row.id, name:row.name, sortOrder:row.sortOrder, status:row.status, grabCaptchaRequired:row.grabCaptchaRequired??1, priceIncreaseRate:Number(row.priceIncreaseRate||0), withdrawDepositAmount:Number(row.withdrawDepositAmount||0) } : { id:null, name:'', sortOrder:10, status:1, grabCaptchaRequired:1, priceIncreaseRate:0, withdrawDepositAmount:0 })
  visible.value=true
}
async function save() {
  if(!form.name.trim()) return toast.warning('请输入分类名称')
  if(!Number.isFinite(form.priceIncreaseRate)||!Number.isFinite(form.withdrawDepositAmount)) return toast.warning('请输入有效的涨价比例和提现押金')
  saving.value=true
  try {
    const data={name:form.name.trim(),sortOrder:form.sortOrder,status:form.status,grabCaptchaRequired:form.grabCaptchaRequired,priceIncreaseRate:form.priceIncreaseRate,withdrawDepositAmount:form.withdrawDepositAmount}
    if(form.id&&original.value&&Number(form.grabCaptchaRequired)===Number(original.value.grabCaptchaRequired)) delete data.grabCaptchaRequired
    if(form.id) await playerLevelUpdate(form.id,data); else await playerLevelCreate(data)
    toast.success('已保存');visible.value=false;load()
  } finally { saving.value=false }
}
async function remove(row) {
  if (!(await confirmAction(`删除「${row.name}」？分类下仍有打手时无法删除，可改为停用。`, '删除分类'))) return
  await playerLevelDelete(row.id);toast.success('已删除');load()
}
</script>
<style scoped>
.tip { color:var(--el-text-color-secondary); font-size:13px; margin:0 0 20px; line-height:1.7; }
.unit { margin-left:8px; color:var(--el-text-color-regular); }
.tip-inline { width:100%; margin:8px 0 0; color:var(--el-text-color-secondary); font-size:12px; line-height:1.6; }
</style>





