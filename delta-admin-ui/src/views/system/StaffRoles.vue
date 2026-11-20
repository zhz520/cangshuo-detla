<template>
  <el-card>
    <template #header>
      <PageHeader title="角色与权限" description="配置后台角色、权限范围和员工归属。">
        <template #actions><el-button type="primary" @click="edit()">新增角色</el-button></template>
      </PageHeader>
    </template>
    <el-alert title="退款为独立权限，默认仅管理员拥有。可为客服等角色单独授权；变更立即在接口生效。" :closable="false"/>
    <DataTable :loading="busy" :data="data.roles">
      <el-table-column prop="name" label="角色" min-width="130"/>
      <el-table-column label="权限" min-width="300">
        <template #default="{row}"><el-tag v-for="permission in row.permissions" :key="permission" class="permission-tag">{{ labels[permission] || permission }}</el-tag></template>
      </el-table-column>
      <el-table-column label="状态"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{row}">
          <el-button v-if="row.code !== 'DEMO'" link @click="edit(row)">编辑</el-button>
          <el-button v-if="!row.builtin && row.code !== 'DEMO'" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </DataTable>
    <h3 class="section-title">员工角色分配</h3>
    <DataTable :loading="busy" :data="data.members">
      <el-table-column prop="username" label="账号"/>
      <el-table-column prop="nickname" label="姓名"/>
      <el-table-column label="基础身份"><template #default="{row}">{{ String(row.role).toUpperCase() === 'ADMIN' ? '管理员' : '客服' }}</template></el-table-column>
      <el-table-column label="权限角色" min-width="210">
        <template #default="{row}">
          <el-select :model-value="row.role_code || String(row.role).toUpperCase()" @change="assign(row.id, $event)">
            <el-option v-for="role in data.roles.filter(x => x.enabled)" :key="role.code" :value="role.code" :label="role.name"/>
          </el-select>
        </template>
      </el-table-column>
    </DataTable>
    <el-dialog v-model="visible" title="设置权限角色" width="650px">
      <el-form label-position="top">
        <el-form-item label="角色编号"><el-input v-model="form.code" :disabled="editing" maxlength="64"/></el-form-item>
        <el-form-item label="角色名称"><el-input v-model="form.name" maxlength="100"/></el-form-item>
        <el-form-item label="权限范围">
          <el-checkbox-group v-model="form.permissions">
            <el-checkbox v-for="code in data.permissionCodes" :key="code" :value="code">{{ labels[code] || code }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-switch v-model="form.enabled" active-text="启用"/>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存角色</el-button></template>
    </el-dialog>
  </el-card>
</template>
<script setup>
import {reactive,ref,onMounted} from 'vue'
import { getStaffRoles, saveStaffRole, assignStaffRole, deleteStaffRole } from '@/api/system'
import {loadStaffPermissions} from '@/utils/staff-permissions'
import {toast, confirmAction} from '@/utils/feedback'
import PageHeader from '@/components/PageHeader.vue'
import DataTable from '@/components/DataTable.vue'
const data=reactive({roles:[],members:[],permissionCodes:[]}),form=reactive({code:'',name:'',permissions:[],enabled:true}),busy=ref(false),saving=ref(false),visible=ref(false),editing=ref(false)
const labels={'demo.read':'查看演示后台 · 不可修改或导出','*':'全部常规权限','order.read':'查看订单','order.manage':'处理订单','order.refund':'执行退款（含售后仲裁）','product.manage':'管理商品与分类','player.manage':'管理打手与邀请','media.review':'审核主页素材','user.read':'查看用户','user.manage':'管理用户、隐私与实名资料','finance.read':'查看财务','finance.manage':'调整用户余额','finance.export':'导出对账Excel','withdraw.review':'审核提现','chat.manage':'客服聊天','system.manage':'系统和角色管理'}
async function load(){busy.value=true;try{Object.assign(data,(await getStaffRoles()).data)}finally{busy.value=false}}
function edit(row){editing.value=!!row;Object.assign(form,row?{...row,permissions:[...row.permissions],enabled:!!row.enabled}:{code:'',name:'',permissions:[],enabled:true});visible.value=true}
async function save(){saving.value=true;try{await saveStaffRole(form);await loadStaffPermissions(true);visible.value=false;await load();toast.success('角色权限已保存')}finally{saving.value=false}}
async function assign(id,code){if(!(await confirmAction('确认调整该员工的操作权限？','分配角色')))return;await assignStaffRole(id, code);await load()}
async function remove(row){if(!(await confirmAction('确认删除角色“'+row.name+'”？','删除角色')))return;await deleteStaffRole(row.code);await load()}
onMounted(load)
</script>
<style scoped>
.permission-tag{margin:3px}
.el-alert{margin-bottom:20px}
.section-title{margin:24px 0 12px;font-size:14px;font-weight:600;color:var(--el-text-color-primary)}
.el-checkbox-group{display:flex;flex-wrap:wrap;gap:8px 18px}
</style>

