<template>
  <el-dialog v-model="visible" title="修改密码" width="420px" destroy-on-close @close="handleClose">
    <el-alert title="修改成功后，其他设备上的登录状态会失效，请使用新密码重新登录。" type="info" :closable="false" show-icon />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="password-form" @submit.prevent="handleSave">
      <el-form-item label="原密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" placeholder="8-32 位，须同时包含字母和数字" maxlength="32" />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmation">
        <el-input v-model="form.confirmation" type="password" show-password autocomplete="new-password" maxlength="32" @keyup.enter="handleSave" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSave">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { changeCsPassword } from '@/api/auth'

const props = defineProps({ modelValue: { type: Boolean, default: false } })
const emit = defineEmits(['update:modelValue', 'changed'])

const visible = ref(props.modelValue)
const loading = ref(false)
const formRef = ref(null)
const form = reactive({ oldPassword: '', newPassword: '', confirmation: '' })

watch(() => props.modelValue, value => { visible.value = value })
watch(visible, value => {
  if (!value) {
    emit('update:modelValue', false)
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmation = ''
  }
})

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 32, message: '密码长度须为 8-32 位', trigger: 'blur' },
    { validator: (_, value, callback) => (/.*[A-Za-z].*/.test(value || '') && /.*[0-9].*/.test(value || '')) ? callback() : callback(new Error('密码须同时包含字母和数字')), trigger: 'blur' }
  ],
  confirmation: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: (_, value, callback) => value === form.newPassword ? callback() : callback(new Error('两次输入的新密码不一致')), trigger: 'blur' }
  ]
}

function handleClose() {
  emit('update:modelValue', false)
}

async function handleSave() {
  if (!formRef.value || loading.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await changeCsPassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
      confirmation: form.confirmation
    })
    visible.value = false
    ElMessage.success('密码修改成功，请重新登录')
    emit('changed')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.password-form { margin-top: 18px; }
</style>
