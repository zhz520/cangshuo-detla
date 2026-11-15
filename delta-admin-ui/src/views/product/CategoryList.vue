<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>分类管理</span>
          <div class="header-right">
            <el-radio-group v-model="parentFilter" size="small" @change="onParentFilterChange">
              <el-radio-button :value="0">全部</el-radio-button>
              <el-radio-button v-for="p in parentList" :key="p.id" :value="p.id">{{ p.name }}</el-radio-button>
            </el-radio-group>
            <el-button type="primary" @click="openDialog()">新增分类</el-button>
          </div>
        </div>
      </template>
      <el-table
        ref="tableRef"
        :data="filteredTree"
        v-loading="loading"
        stripe
        row-key="id"
        :default-expand-all="false"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="分类名称">
          <template #default="{ row }">
            <span :class="{ 'parent-name': !row.parentId }">{{ row.name }}</span>
            <el-tag v-if="!row.parentId" size="small" type="info" class="level-tag">父分类</el-tag>
            <el-tag v-else size="small" class="level-tag">子分类</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="icon" label="图标" width="90">
          <template #default="{ row }">
            <el-image lazy v-if="row.icon" :src="iconPreview(row.icon)" style="width:50px;height:50px" fit="contain" :preview-src-list="[iconPreview(row.icon)]" preview-teleported />
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              @change="handleToggleStatus(row)"
              active-text="启用" inactive-text="禁用" inline-prompt
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button v-if="!row.parentId" link type="primary" @click="openDialog(null, row.id)">添加子分类</el-button>
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-popconfirm :title="!row.parentId ? '删除父分类会同时删除其下所有子分类，确认？' : '确认删除？'" @confirm="handleDelete(row.id)">
              <template #reference><el-button link type="danger">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="类型" prop="parentId">
          <el-select v-model="form.parentId" :disabled="!!editingId" clearable placeholder="无（创建为父分类）">
            <el-option v-for="c in parentList" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <div class="form-tip" v-if="!editingId">
            {{ form.parentId ? '将作为子分类添加' : '将作为父分类添加（可包含子分类）' }}
          </div>
        </el-form-item>
        <el-form-item label="图标">
          <div class="icon-editor">
            <div class="icon-current">
              <el-image v-if="form.icon" :src="iconPreview(form.icon)" fit="contain" />
              <span v-else>未设置</span>
            </div>
            <div class="icon-actions">
              <el-button size="small" @click="iconLibraryVisible = true">从图标库选择</el-button>
              <ImageUpload v-model="form.icon" />
              <el-button v-if="form.icon" link type="danger" @click="form.icon = ''">清空</el-button>
              <div class="form-tip">图标库为统一线性图标；上传图建议 1:1、透明背景、最小 200×200。非正方形会完整显示并留白，不会被强行裁切。</div>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="图标大小">
          <el-slider v-model="form.iconSize" :min="40" :max="80" :step="4" show-input />
          <div class="form-tip">控制用户 H5 首页分类入口的图标尺寸，推荐 56rpx；文字和图标会按同一基线对齐。</div>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="iconLibraryVisible" title="选择分类图标" width="760px" destroy-on-close>
      <div class="icon-library-intro">统一线性图标，透明背景，1:1 适配前台。分类需要辨识度时优先使用图标库；品牌图标可自行上传。</div>
      <div v-for="group in iconGroups" :key="group.title" class="icon-group">
        <div class="icon-group-title">{{ group.title }}</div>
        <div class="icon-library">
          <button v-for="name in group.icons" :key="name" type="button" class="icon-option" :class="{ active: form.icon === '/static/category-icons/' + name + '.svg' }" @click="chooseIcon(name)">
            <img :src="'/static/category-icons/' + name + '.svg'" alt="" />
            <span>{{ iconLabels[name] || name }}</span>
          </button>
        </div>
      </div>
      <template #footer><el-button @click="iconLibraryVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { getCategoryTree, addCategory, updateCategory, deleteCategory } from '@/api/product'
import { ElMessage } from 'element-plus'
import ImageUpload from '@/components/ImageUpload.vue'
import {thumbnailUrl} from '@/utils/image'

const loading = ref(false)
const tree = ref([])
const parentFilter = ref(0)
const tableRef = ref(null)

const parentList = computed(() => tree.value.map(p => ({ id: p.id, name: p.name })))

const filteredTree = computed(() => {
  if (!parentFilter.value) return tree.value
  return tree.value.filter(p => p.id === parentFilter.value)
})

async function fetchData() {
  loading.value = true
  try {
    const res = await getCategoryTree()
    tree.value = (res.data || []).map(p => ({
      ...p,
      children: p.children?.length ? p.children : undefined,
      hasChildren: !!(p.children?.length)
    }))
  } finally {
    loading.value = false
  }
}

function onParentFilterChange() {
  // filter changed, tree re-renders
}

const dialogVisible = ref(false)
const editingId = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({ name: '', parentId: null, icon: '', iconSize: 56, sortOrder: 0, status: 1 })
const defaultForm = { name: '', parentId: null, icon: '', iconSize: 56, sortOrder: 0, status: 1 }
const iconLibraryVisible = ref(false)
const iconGroups = [
  { title: '服务保障', icons: ['shield-check', 'target', 'clock', 'calendar', 'heart', 'pin'] },
  { title: '游戏与陪玩', icons: ['headset', 'gamepad', 'sword', 'users', 'trophy', 'flame'] },
  { title: '训练与设备', icons: ['chart', 'monitor', 'mouse', 'keyboard', 'compass', 'rocket'] },
  { title: '内容与互动', icons: ['chat', 'star', 'gift', 'medal', 'crown', 'tag'] },
  { title: '数据与资产', icons: ['wallet', 'ranking'] }
]
const iconLabels = { 'shield-check':'护航','headset':'陪玩','chart':'训练','trophy':'挑战','sword':'对战','target':'精准','users':'组队','clock':'限时','gift':'福利','star':'精选','medal':'荣誉','compass':'探索','gamepad':'游戏','crown':'会员','flame':'热门','rocket':'极速','keyboard':'键鼠','mouse':'鼠标','monitor':'设备','chat':'会话','wallet':'钱包','heart':'喜欢','ranking':'排行','calendar':'预约','tag':'标签','pin':'位置' }
const iconPreview = icon => icon || ''
function chooseIcon(name) { form.icon = '/static/category-icons/' + name + '.svg'; iconLibraryVisible.value = false }
const rules = { name: [{ required: true, message: '请输入名称', trigger: 'blur' }] }

const dialogTitle = computed(() => {
  if (editingId.value) return '编辑分类'
  if (form.parentId) return '新增子分类'
  return '新增分类'
})

function openDialog(row, presetParentId) {
  editingId.value = row?.id || null
  if (row) {
    Object.assign(form, { name: row.name, parentId: row.parentId || null, icon: row.icon || '', iconSize: row.iconSize || 56, sortOrder: row.sortOrder || 0, status: row.status ?? 1 })
  } else {
    Object.assign(form, { ...defaultForm })
    if (presetParentId) form.parentId = presetParentId
  }
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const payload = { ...form }
    if (!payload.parentId) payload.parentId = null
    if (editingId.value) {
      await updateCategory({ ...payload, id: editingId.value })
    } else {
      await addCategory(payload)
    }
    ElMessage.success('操作成功')
    dialogVisible.value = false
    fetchData()
  } finally {
    submitting.value = false
  }
}

async function handleToggleStatus(row) {
  const newStatus = row.status === 1 ? 0 : 1
  await updateCategory({ ...row, status: newStatus, children: undefined })
  ElMessage.success(newStatus === 1 ? '已启用' : '已禁用')
  fetchData()
}

async function handleDelete(id) {
  await deleteCategory(id)
  ElMessage.success('删除成功')
  fetchData()
}

onMounted(fetchData)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  min-width: 0;
}
.parent-name {
  font-weight: 600;
}
.level-tag {
  margin-left: 8px;
}
.form-tip {
  font-size: 12px;
  color: #73777e;
  margin-top: 4px;
}
.icon-editor { display: flex; align-items: flex-start; gap: 14px; width: 100%; }
.icon-current { width: 76px; height: 76px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; border-radius: 16px; background: #f5f6f7; color: #aaa; font-size: 12px; overflow: hidden; }
.icon-current .el-image { width: 52px; height: 52px; }
.icon-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; }
.icon-library { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.icon-library-intro { margin-bottom: 16px; padding: 12px 14px; border-radius: 12px; background: #f6f7f8; color: #777; font-size: 12px; line-height: 1.6; }
.icon-group { margin-top: 16px; }
.icon-group-title { margin-bottom: 10px; color: #555; font-size: 12px; font-weight: 700; }
.icon-option { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; min-height: 100px; padding: 12px; border: 1px solid #eeeeee; border-radius: 14px; background: #ffffff; color: #555; cursor: pointer; }
.icon-option img { width: 34px; height: 34px; }
.icon-option span { font-size: 12px; }
.icon-option:hover, .icon-option.active { border-color: #171717; background: #f7f7f7; color: #111; }
@media (max-width: 768px) { .icon-library { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
</style>
