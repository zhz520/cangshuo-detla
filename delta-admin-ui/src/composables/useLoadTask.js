import { onScopeDispose, ref } from 'vue'
import { createLatestTask } from '@/utils/latest-task'

export function useLoadTask(loader, initialValue = null) {
  const data = ref(initialValue)
  const loading = ref(false)
  const error = ref('')
  const task = createLatestTask(loader, {
    start: () => { loading.value = true; error.value = '' },
    success: value => { data.value = value },
    failure: reason => { error.value = reason?.message || '加载失败，请稍后重试' },
    finish: () => { loading.value = false }
  })
  onScopeDispose(task.dispose)
  return { data, loading, error, run: task.run }
}
