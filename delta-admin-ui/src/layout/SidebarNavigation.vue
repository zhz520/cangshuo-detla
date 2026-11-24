<template>
  <nav class="admin-navigation" aria-label="主导航">
    <div class="logo">
      <div class="logo-mark"><img v-if="logo" :src="logo" alt="" /><template v-else>{{ initial }}</template></div>
      <h1 v-if="!collapsed" :title="title">{{ title }}</h1>
      <slot name="action" />
    </div>
    <el-menu ref="menu" :default-active="active" :default-openeds="opened" :collapse="collapsed" :collapse-transition="false" unique-opened router class="layout-menu" @select="index => $emit('select', index)">
      <el-menu-item-group v-for="group in groups" :key="group.id" :title="collapsed ? '' : group.title">
        <template v-for="item in group.items" :key="item.id || item.path">
          <el-menu-item v-if="!item.children" :index="item.path" :aria-current="item.path === active ? 'location' : undefined">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.title }} <i v-if="badges[item.path]" class="nav-dot" aria-label="有未读待办" /></template>
          </el-menu-item>
          <el-sub-menu v-else :index="item.id" popper-class="navigation-popup">
            <template #title><el-icon><component :is="item.icon" /></el-icon><span>{{ item.title }} <i v-if="item.children.some(child => badges[child.path])" class="nav-dot" aria-label="有未读待办" /></span></template>
            <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path" :aria-current="child.path === active ? 'location' : undefined">{{ child.title }} <i v-if="badges[child.path]" class="nav-dot" aria-label="有未读待办" /></el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu-item-group>
    </el-menu>
  </nav>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { activeNavigationMenu } from '@/utils/navigation'

const props = defineProps({
  collapsed: Boolean,
  active: String,
  groups: { type: Array, default: () => [] },
  title: String,
  initial: String,
  logo: String,
  badges: { type: Object, default: () => ({}) }
})
defineEmits(['select'])
const menu = ref(null)
const opened = computed(() => {
  const parent = activeNavigationMenu(props.active, props.groups)
  return parent ? [parent] : []
})
watch([() => props.active, () => props.collapsed, () => props.groups], async () => {
  await nextTick()
  if (!props.collapsed && opened.value.length) menu.value?.open(opened.value[0])
}, { flush: 'post' })
</script>

<style lang="scss" scoped>
.nav-dot { display:inline-block; width:7px; height:7px; margin-left:4px; background:#f16e7a; border-radius:50%; vertical-align:middle; }
.admin-navigation { display:flex; flex-direction:column; height:100%; min-height:0; background:#ffffff; }
.logo { flex-shrink:0; min-height:68px; padding:14px 16px; display:flex; align-items:center; gap:10px; color:#171717; border-bottom:0; }
.logo-mark { width:34px; height:34px; border-radius:10px; display:flex; align-items:center; justify-content:center; flex-shrink:0; color:#ffffff; font-size:17px; font-weight:800; background:#171717; }
.logo-mark img { width:100%; height:100%; object-fit:cover; border-radius:inherit; }
h1 { flex:1; min-width:0; font-size:15px; line-height:1.2; font-weight:700; margin:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.layout-menu {
  flex:1; min-height:0; width:100%; border-right:none; overflow-y:auto; overflow-x:hidden; overscroll-behavior:contain;
  -webkit-overflow-scrolling:touch; scrollbar-width:thin; scrollbar-color:#50545b transparent;
  padding:4px 10px calc(18px + var(--app-safe-bottom,0px)); background:transparent;
  :deep(.el-menu) { background:transparent; }
  :deep(.el-menu-item-group__title) { padding:18px 12px 6px!important; color:#aaaaaa; font-size:11px; line-height:20px; letter-spacing:1px; }
  :deep(.el-menu-item), :deep(.el-sub-menu__title) { height:42px; margin:3px 0; border-radius:10px; color:#656b73; font-size:14px; transition:background-color var(--delta-motion-tab, 200ms) var(--delta-motion-ease),color var(--delta-motion-tab, 200ms) var(--delta-motion-ease); }
  :deep(.el-menu-item:hover), :deep(.el-sub-menu__title:hover) { color:#171717; background:#f7f7f7; }
  :deep(.el-sub-menu.is-active > .el-sub-menu__title) { color:#171717; font-weight:700; }
  :deep(.el-sub-menu .el-menu-item) { min-width:0; height:38px; padding-left:46px!important; font-size:13px; }
  :deep(.el-menu-item.is-active) { color:#171717; font-weight:800; background:#f1f2f4; box-shadow:none; }
  &.el-menu--collapse { width:64px; padding-inline:6px; }
  &.el-menu--collapse :deep(.el-menu-item-group__title) { padding:7px 0!important; height:14px; }
  &.el-menu--collapse :deep(.el-menu-item-group + .el-menu-item-group) { border-top:1px solid #f1f2f4; }
  &.el-menu--collapse :deep(.el-sub-menu__title), &.el-menu--collapse :deep(.el-menu-item > .el-menu-tooltip__trigger) { padding:0!important; justify-content:center; }
  &.el-menu--collapse :deep(.el-icon) { margin:0; }
}
@media(max-width:1024px) {
  .logo { padding-top:calc(14px + var(--app-safe-top,0px)); }
  .layout-menu :deep(.el-menu-item), .layout-menu :deep(.el-sub-menu__title) { min-height:44px; }
}
</style>
<style>
.navigation-popup .el-menu-item { min-width:170px; font-size:13px; }
</style>
