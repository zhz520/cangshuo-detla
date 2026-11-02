import { defineStore } from 'pinia'
import { ref } from 'vue'
import { get } from '@/api/request'

export const useOfficialWechatStore = defineStore('official-wechat', () => {
  const statusByRole = ref({})
  const pending = new Map()
  async function load(role = 'user') {
    if (pending.has(role)) return pending.get(role)
    const task = get('/common/wechat/binding', {}, { role, loading: false })
      .then(response => { statusByRole.value = { ...statusByRole.value, [role]: response.data || {} }; return response.data })
      .catch(() => { statusByRole.value = { ...statusByRole.value, [role]: { enabled: false, licensed: false } }; return null })
      .finally(() => pending.delete(role))
    pending.set(role, task)
    return task
  }
  const isEnabled = role => statusByRole.value[role]?.enabled === true
  return { statusByRole, load, isEnabled }
})
