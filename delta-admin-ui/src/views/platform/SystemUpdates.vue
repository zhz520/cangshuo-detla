<template>
  <div class="updates-page" v-loading="loading">
    <el-card shadow="never">
      <template #header><div class="heading"><h2>系统更新</h2><el-button @click="load">刷新版本信息</el-button></div></template>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-if="info && overview">
        <el-descriptions :column="isCompact ? 1 : 2" border>
          <el-descriptions-item label="当前版本">{{ info.version }}</el-descriptions-item>
          <el-descriptions-item label="发布通道">{{ info.channel === 'stable' ? '正式版' : '预览验证' }}</el-descriptions-item>
          <el-descriptions-item label="客户编号">{{ overview.customerId }}</el-descriptions-item>
          <el-descriptions-item label="更新方式">维护人员手动部署</el-descriptions-item>
        </el-descriptions>
        <el-alert class="notice" :title="info.message" type="info" :closable="false" show-icon />
        <p>当前使用 GitHub 正式版 {{ info.version }}。<a :href="info.sourceUrl" target="_blank" rel="noopener noreferrer">查看 Release</a></p>
      </template>
    </el-card>
    <el-card v-if="info" shadow="never">
      <template #header>本版本变更</template>
      <ul><li v-for="note in info.releaseNotes" :key="note">{{ note }}</li></ul>
    </el-card>
    <el-card v-if="info" shadow="never">
      <template #header><div class="heading"><span>选择更新内容</span><el-button type="primary" :loading="saving" @click="saveSelection">保存选择</el-button></div></template>
      <p>勾选本实例下次维护升级需要包含的内容。该选择会保存在实例内，实际安装仍由维护人员执行。</p>
      <el-checkbox-group v-model="selected"><el-checkbox v-for="item in info.components" :key="item.id" :value="item.id"><strong>{{ item.name }}</strong><span class="component-tip">{{ item.description }}</span></el-checkbox></el-checkbox-group>
    </el-card>
    <el-card shadow="never">
      <template #header>更新保护范围</template>
      <p>品牌、域名、Logo、支付配置、上传文件和业务数据属于本实例配置，后续版本更新应独立保留。</p>
      <p>自助升级将在正式版本源、实例备份、数据库迁移与回退验证接入后开放。当前页面不执行安装操作。</p>
      <el-button @click="$router.push('/platform/features')">查看功能与授权</el-button>
    </el-card>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useResponsive } from '@/utils/responsive'
import { getPlatformOverview, getPlatformUpdates, setPlatformUpdateSelection } from '@/api/platform'
const { isCompact } = useResponsive()
const info = ref(null), overview = ref(null), loading = ref(false), saving = ref(false), error = ref(''), selected = ref([])
async function load() {
  loading.value = true; error.value = ''
  try { const [status, identity] = await Promise.all([getPlatformUpdates(), getPlatformOverview()]); info.value = status.data; overview.value = identity.data; selected.value = (status.data.components || []).filter(item => item.selected).map(item => item.id) }
  catch { error.value = '版本信息加载失败，请刷新重试。'; info.value = null; overview.value = null }
  finally { loading.value = false }
}
async function saveSelection() {
  saving.value = true
  try { info.value = (await setPlatformUpdateSelection(selected.value)).data; ElMessage.success('更新内容已保存') }
  catch { error.value = '更新内容保存失败，请稍后重试。' }
  finally { saving.value = false }
}
onMounted(load)
</script>
<style scoped>
.updates-page { display: grid; gap: 18px; }
.heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
p, li { color: #555b63; line-height: 1.9; font-size: 14px; }
ul { padding-left: 22px; margin: 0; }
.notice { margin-top: 18px; }
.component-tip { color: #7b879a; margin-left: 10px; font-size: 12px; }
:deep(.el-checkbox-group) { display: grid; gap: 14px; }
:deep(.el-checkbox) { height: auto; align-items: flex-start; }
</style>
