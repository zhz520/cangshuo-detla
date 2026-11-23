<template>
  <div class="phone" @keydown.esc.stop="dismissWithFocus">
    <div class="title">{{ accountName || '微信公众号' }}</div>
    <div class="conversation" @click="openId = null"><span role="status">{{ actionText }}</span></div>
    <div class="bar" :style="{ '--count': Math.max(1, roots.length), '--index': openIndex }">
      <span aria-hidden="true" class="keyboard">⌨</span>
      <span v-if="!roots.length" class="empty">菜单将显示在这里</span>
      <div v-for="root in roots" :key="root.id" class="slot">
        <button type="button" class="trigger" :data-root="root.id"
          :aria-expanded="root.children.length ? root.id === openId : undefined" @click="activate(root)">
          <span v-if="root.children.length" aria-hidden="true" class="list-icon">≡</span>
          <span class="label">{{ root.name || '未命名菜单' }}</span>
        </button>
        <ul v-if="root.id === openId && root.children.length" class="submenu" :aria-label="(root.name || '未命名菜单') + '的子菜单'">
          <li v-for="child in root.children" :key="child.id">
            <button type="button" :title="child.name" @click="showAction(child, $event)">{{ child.name || '未命名子菜单' }}</button>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { menuType } from './menu-model'

const props = defineProps({ roots: { type: Array, required: true }, accountName: String })
const openId = ref(null), actionText = ref('')
const openIndex = computed(() => props.roots.findIndex(root => root.id === openId.value && root.children.length))
watch(openIndex, index => { if (index < 0) openId.value = null })
watch(() => props.roots, () => { openId.value = null; actionText.value = '' })
function activate(root) {
  if (!root.children.length) return showAction(root)
  actionText.value = ''
  openId.value = openId.value === root.id ? null : root.id
}
function dismissWithFocus(event) {
  if (openId.value == null) return
  event.currentTarget.closest('.phone')?.querySelector('[aria-expanded="true"]')?.focus()
  openId.value = null
}
function showAction(node, event) {
  if (event) dismissWithFocus(event)
  openId.value = null
  actionText.value = menuType(node.type)?.label || '未配置动作'
}
</script>

<style scoped>
.phone{width:100%;max-width:375px;box-sizing:border-box;margin-inline:auto;overflow:hidden;border:1px solid var(--el-border-color-light);border-radius:14px;background:var(--el-fill-color-light);color:var(--el-text-color-primary)}
.title,.bar{text-align:center;background:var(--el-bg-color);border-color:var(--el-border-color-light)}
.title{padding:10px;font-weight:600;border-bottom:1px solid var(--el-border-color-light)}
.conversation{box-sizing:border-box;min-height:250px;padding:12px;color:var(--el-text-color-secondary)}
.bar{--keyboard:36px;--bubble:min(9em, calc(100% - 12px));--center:calc(var(--keyboard) + (100% - var(--keyboard)) / var(--count) * (var(--index) + .5));position:relative;display:flex;align-items:stretch;border-top:1px solid var(--el-border-color-light);font-size:14px}
.keyboard,.empty{align-self:center;color:var(--el-text-color-secondary)}.keyboard{flex:0 0 var(--keyboard);font-size:20px}.empty{flex:1;font-size:12px}
.slot{flex:1 1 0;min-width:0}.bar button{font:inherit;color:inherit;background:none;cursor:pointer;border:0}
.trigger{box-sizing:border-box;position:relative;display:flex;align-items:center;justify-content:center;gap:3px;width:100%;height:46px;padding:0 4px;box-shadow:inset 1px 0 var(--el-border-color-light)}
.label,.submenu button{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.list-icon{color:var(--el-text-color-secondary);font-size:12px}
.bar button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}
.submenu{box-sizing:border-box;position:absolute;z-index:1;bottom:calc(100% + 10px);left:clamp(6px,calc(var(--center) - var(--bubble) / 2),calc(100% - var(--bubble) - 6px));width:var(--bubble);margin:0;padding:0 8px;list-style:none;background:var(--el-bg-color);border:1px solid var(--el-border-color-light);border-radius:6px;box-shadow:var(--el-box-shadow-light)}
.submenu li+li{border-top:1px solid var(--el-border-color-lighter)}
.submenu button{display:block;width:100%;height:44px;text-align:center;padding:0 2px}
.trigger[aria-expanded="true"]::after{content:"";position:absolute;z-index:2;bottom:calc(100% + 6px);left:50%;width:8px;height:8px;background:var(--el-bg-color);border:solid var(--el-border-color-light);border-width:0 1px 1px 0;transform:translateX(-50%) rotate(45deg);pointer-events:none}
</style>
