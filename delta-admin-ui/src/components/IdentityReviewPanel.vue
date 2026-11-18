<template>
  <div class="identity-review-panel" v-loading="loading">
    <el-empty v-if="!loading && !detail" description="该用户尚未提交实名认证" />
    <template v-else-if="detail">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="姓名">{{ detail.real_name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="证件号码">{{ detail.id_number_masked || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag :type="tag(detail.status)">{{ labels[detail.status] || detail.status }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ detail.submitted_at || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审核时间">{{ detail.reviewed_at || '-' }}</el-descriptions-item>
        <el-descriptions-item label="失败次数">{{ detail.failed_attempts || 0 }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.locked_until" label="锁定至">{{ detail.locked_until }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.review_note" label="审核说明">{{ detail.review_note }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="detail.frontImage || detail.backImage" class="documents">
        <el-image v-if="detail.frontImage" :src="detail.frontImage" :preview-src-list="[detail.frontImage, detail.backImage].filter(Boolean)" fit="contain" />
        <el-image v-if="detail.backImage" :src="detail.backImage" :preview-src-list="[detail.frontImage, detail.backImage].filter(Boolean)" fit="contain" />
      </div>
      <el-form v-if="detail.status === 'PENDING'" label-position="top" class="review-form">
        <el-form-item label="审核结果"><el-radio-group v-model="decision.approved"><el-radio :value="true">通过</el-radio><el-radio :value="false">驳回</el-radio></el-radio-group></el-form-item>
        <el-form-item label="审核说明"><el-input v-model="decision.note" type="textarea" maxlength="500" :placeholder="decision.approved ? '可选' : '驳回时必须填写原因'" /></el-form-item>
      </el-form>
      <div v-if="detail.status === 'PENDING'" class="actions">
        <el-button type="primary" :loading="saving" @click="submit">确认审核</el-button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getIdentityApplication, reviewIdentityApplication } from '@/api/identity'

const props = defineProps({ userId: { type: [Number, String], required: true } })
const emit = defineEmits(['reviewed'])
const loading = ref(false), saving = ref(false), detail = ref(null)
const decision = reactive({ approved: true, note: '' })
const labels = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }
const tag = value => value === 'APPROVED' ? 'success' : value === 'REJECTED' ? 'danger' : 'warning'

async function load() {
  if (!props.userId) return
  loading.value = true
  detail.value = null
  decision.approved = true
  decision.note = ''
  try {
    detail.value = (await getIdentityApplication(props.userId)).data
  } catch (error) {
    if (error?.code !== 404) ElMessage.error(error?.message || '实名认证资料加载失败')
  } finally { loading.value = false }
}

async function submit() {
  if (saving.value || !detail.value) return
  if (!decision.approved && !decision.note.trim()) return ElMessage.warning('驳回时必须填写原因')
  saving.value = true
  try {
    await reviewIdentityApplication(detail.value.user_id, { approved: decision.approved, note: decision.note })
    ElMessage.success('审核结果已保存')
    emit('reviewed', detail.value.user_id)
    await load()
  } catch (error) { ElMessage.error(error?.message || '审核失败') }
  finally { saving.value = false }
}

watch(() => props.userId, load, { immediate: true })
</script>

<style scoped>
.identity-review-panel { min-height: 80px; }
.documents { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin: 20px 0; }
.documents :deep(.el-image) { width: 100%; height: 240px; border: 1px solid #e6e7e9; border-radius: 12px; background: #f7f8f9; }
.review-form { margin-top: 16px; }
.actions { display: flex; justify-content: flex-end; }
@media (max-width: 640px) { .documents { grid-template-columns: 1fr; } .documents :deep(.el-image) { height: 200px; } }
</style>
