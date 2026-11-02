<template>
  <div class="page-container">
    <el-card v-loading="loading">
      <template #header><div class="toolbar"><strong>实名认证审核</strong><div><el-select v-model="status" clearable placeholder="全部状态" style="width:140px" @change="load"><el-option label="待审核" value="PENDING" /><el-option label="已通过" value="APPROVED" /><el-option label="已驳回" value="REJECTED" /></el-select><el-button @click="load">刷新</el-button></div></div></template>
      <el-alert title="证件照片仅供实名审核，审核完成后自动清除；证件号码仅保存不可逆摘要和脱敏号码。" type="info" :closable="false" />
      <el-table :data="rows" stripe>
        <el-table-column prop="user_id" label="用户ID" width="90" />
        <el-table-column prop="nickname" label="用户昵称" min-width="130" />
        <el-table-column prop="real_name" label="姓名" min-width="120" />
        <el-table-column prop="id_number_masked" label="证件号码" min-width="160" />
        <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="tag(row.status)">{{ labels[row.status] || row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="submitted_at" label="提交时间" min-width="180" />
        <el-table-column prop="reviewed_at" label="审核时间" min-width="180" />
        <el-table-column label="操作" width="110" fixed="right"><template #default="{row}"><el-button link type="primary" @click="open(row)">查看</el-button></template></el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无实名认证申请" />
    </el-card>

    <el-dialog v-model="visible" title="实名认证审核" width="min(760px, 96vw)" destroy-on-close>
      <div v-if="detail" v-loading="detailLoading">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="用户">{{ detail.nickname }}（{{ detail.user_id }}）</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ detail.real_name }}</el-descriptions-item>
          <el-descriptions-item label="证件号码">{{ detail.id_number_masked }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ labels[detail.status] || detail.status }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ detail.submitted_at || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.review_note" label="审核说明">{{ detail.review_note }}</el-descriptions-item>
        </el-descriptions>
        <div class="documents" v-if="detail.frontImage || detail.backImage">
          <el-image v-if="detail.frontImage" :src="detail.frontImage" :preview-src-list="[detail.frontImage, detail.backImage].filter(Boolean)" fit="contain" />
          <el-image v-if="detail.backImage" :src="detail.backImage" :preview-src-list="[detail.frontImage, detail.backImage].filter(Boolean)" fit="contain" />
        </div>
        <el-form v-if="detail.status==='PENDING'" label-position="top" class="review-form">
          <el-form-item label="审核结果"><el-radio-group v-model="decision.approved"><el-radio :value="true">通过</el-radio><el-radio :value="false">驳回</el-radio></el-radio-group></el-form-item>
          <el-form-item label="审核说明"><el-input v-model="decision.note" type="textarea" maxlength="500" :placeholder="decision.approved?'可选':'驳回时必须填写原因'" /></el-form-item>
        </el-form>
      </div>
      <template #footer><el-button @click="visible=false">关闭</el-button><el-button v-if="detail?.status==='PENDING'" type="primary" :loading="saving" :disabled="detailLoading || !detail || detail.user_id!==requestedUserId" @click="submit">确认审核</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getIdentityApplication, getIdentityApplications, reviewIdentityApplication } from '@/api/identity'

const loading=ref(false),detailLoading=ref(false),saving=ref(false),rows=ref([]),status=ref(''),visible=ref(false),detail=ref(null),requestedUserId=ref(null)
const decision=reactive({approved:true,note:''})
const labels={PENDING:'待审核',APPROVED:'已通过',REJECTED:'已驳回'}
const tag=value=>value==='APPROVED'?'success':value==='REJECTED'?'danger':'warning'

async function load(){loading.value=true;try{rows.value=(await getIdentityApplications({status:status.value})).data||[]}finally{loading.value=false}}
async function open(row){visible.value=true;detail.value=null;requestedUserId.value=Number(row.user_id);detailLoading.value=true;decision.approved=true;decision.note='';try{const loaded=(await getIdentityApplication(row.user_id)).data;if(Number(loaded?.user_id)===requestedUserId.value)detail.value=loaded}finally{detailLoading.value=false}}
async function submit(){if(detailLoading.value||!detail.value||Number(detail.value.user_id)!==requestedUserId.value)return ElMessage.warning('资料尚未加载完成');if(!decision.approved&&!decision.note.trim())return ElMessage.warning('驳回时必须填写原因');saving.value=true;try{await reviewIdentityApplication(detail.value.user_id,{approved:decision.approved,note:decision.note});visible.value=false;await load();ElMessage.success('审核结果已保存')}finally{saving.value=false}}
onMounted(load)
</script>

<style scoped>
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}.toolbar>div{display:flex;gap:10px;align-items:center}.documents{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px;margin:22px 0}.documents :deep(.el-image){width:100%;height:260px;border:1px solid #e6e7e9;border-radius:12px;background:#f7f8f9}.review-form{margin-top:20px}@media(max-width:640px){.documents{grid-template-columns:1fr}.documents :deep(.el-image){height:220px}}
</style>
