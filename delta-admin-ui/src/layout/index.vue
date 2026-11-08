<template>
  <el-container class="layout-container">
    <el-aside v-if="!isCompact" :width="isCollapse ? '64px' : '220px'" class="layout-aside">
      <SidebarNavigation :collapsed="isCollapse" :active="activeMenu" :groups="menuGroups" :badges="menuBadges" :title="siteStore.adminTitle" :initial="logoInitial" />
    </el-aside>

    <el-drawer v-model="navigationOpen" direction="ltr" size="300px" :with-header="false" title="主导航" append-to-body class="mobile-navigation">
      <SidebarNavigation mobile :active="activeMenu" :groups="menuGroups" :badges="menuBadges" :title="siteStore.adminTitle" :initial="logoInitial" @select="navigationOpen = false">
        <template #action><button class="navigation-close" type="button" aria-label="关闭导航" @click="navigationOpen = false"><el-icon><Close /></el-icon></button></template>
      </SidebarNavigation>
    </el-drawer>

    <el-container class="layout-body">
      <el-header class="layout-header">
        <div class="header-left">
          <button class="collapse-btn" type="button" :aria-label="isCompact ? '打开导航' : (isCollapse ? '展开导航' : '收起导航')" :aria-expanded="isCompact ? navigationOpen : !isCollapse" @click="toggleNavigation">
            <Fold v-if="!isCompact && !isCollapse" />
            <Expand v-else />
          </button>
          <span class="mobile-page-title">{{ currentPageTitle }}</span>
          <!-- 面包屑 -->
          <el-breadcrumb separator="/">
            <el-breadcrumb-item v-for="item in breadcrumbs" :key="item.path">
              {{ item.meta?.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <button class="chat-notify" type="button" title="待办中心" :aria-label="`待办中心，${workbench.summary?.unread || 0}项未读`" @click="openCenter('tasks')">
            <el-icon :size="20"><List /></el-icon>
            <span v-if="workbench.summary?.unread" class="notify-badge task-badge">{{ badge(workbench.summary.unread) }}</span>
          </button>
          <button class="chat-notify" type="button" title="系统通知" :aria-label="`系统通知，${workbench.summary?.systemUnread || 0}条未读`" @click="openCenter('notices')">
            <el-icon :size="20"><Bell /></el-icon>
            <span v-if="workbench.summary?.systemUnread" class="notify-badge">{{ badge(workbench.summary.systemUnread) }}</span>
          </button>
          <button v-if="can('chat.manage')" class="chat-notify" type="button" @click="goChat" title="客服消息" :aria-label="`客服消息，${workbench.summary?.chatUnread || 0}条未读`">
            <el-icon :size="20"><ChatDotRound /></el-icon>
            <span v-if="workbench.summary?.chatUnread > 0" class="notify-badge chat-badge">{{ badge(workbench.summary?.chatUnread) }}</span>
          </button>
          <el-tag :type="role === 'admin' ? 'danger' : 'warning'" size="small" class="role-tag">
            {{ role === 'admin' ? '管理员' : '客服' }}
          </el-tag>
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-avatar v-if="userStore.avatar" :src="thumbnailUrl(userStore.avatar,160)" :size="28" style="margin-right:8px" />
              <span class="user-name" :title="nickname">{{ nickname }}</span> <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">编辑资料</el-dropdown-item>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main ref="main" class="layout-main" tabindex="-1" :aria-busy="Boolean(loadingFeedback.destination) || loadingFeedback.requests > 0" aria-label="页面内容">
        <LoadFeedback :error="platformFeatureError" label="功能菜单" @retry="loadPlatformFeatureVisibility(true)" />
        <LoadFeedback v-if="loadingFeedback.readError?.path === route.fullPath" :error="loadingFeedback.readError.message" label="页面数据" retry-label="重新加载页面" @retry="reloadPage" />
        <router-view v-slot="{ Component, route: pageRoute }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" :key="`${pageRoute.path}:${pageVersion}`" />
          </transition>
        </router-view>
      </el-main>
    </el-container>

    <nav v-if="isCompact" class="mobile-bottom-nav" aria-label="移动端快捷导航">
      <button type="button" :class="{ active: route.path === '/dashboard' }" @click="router.push('/dashboard')"><el-icon><HomeFilled /></el-icon><span>工作台</span></button>
      <button type="button" :class="{ active: route.path.startsWith('/order') }" @click="router.push('/order/list')"><el-icon><Tickets /></el-icon><span>订单</span></button>
      <button type="button" @click="openCenter('tasks')"><el-icon><Bell /></el-icon><span>待办</span><i v-if="workbench.summary?.unread" class="nav-unread">{{ badge(workbench.summary.unread) }}</i></button>
      <button type="button" :class="{ active: navigationOpen }" @click="navigationOpen = true"><el-icon><Menu /></el-icon><span>菜单</span></button>
    </nav>

    <!-- 编辑资料弹窗 -->
    <ProfileEditDialog v-model="profileDialogVisible" @saved="onProfileSaved" />
    <WorkbenchCenter v-model="centerOpen" :mode="centerMode" />
    <PasswordChangeDialog v-model="passwordDialogVisible" @changed="onPasswordChanged" />
  </el-container>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick, onErrorCaptured } from 'vue'
import {can} from '@/utils/staff-permissions'
import {buildNavigation} from '@/utils/navigation'
import {enabledPlatformFeatures,loadPlatformFeatureVisibility,platformFeatureAllowed,platformFeatureError} from '@/utils/platform-feature-visibility'
import { loadingFeedback } from '@/utils/loading-feedback'
import LoadFeedback from '@/components/LoadFeedback.vue'
import { useRoute, useRouter } from 'vue-router'
import { Fold, Expand, ArrowDown, ChatDotRound, Close, List, Bell, HomeFilled, Tickets, Menu } from '@element-plus/icons-vue'
import SidebarNavigation from './SidebarNavigation.vue'
import { useResponsive } from '@/utils/responsive'
import { useUserStore } from '@/stores/user'
import ProfileEditDialog from '@/components/ProfileEditDialog.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'
import { useChatStore } from '@/stores/chat'
import { useWorkbenchStore } from '@/stores/workbench'
import { taskPaths } from '@/utils/workbench-links'
import WorkbenchCenter from '@/components/WorkbenchCenter.vue'
import { useSiteStore } from '@/stores/site'
import {thumbnailUrl} from '@/utils/image'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()
const workbench = useWorkbenchStore()
const centerOpen = ref(false)
const centerMode = ref('tasks')
function openCenter(mode) { centerMode.value = mode; centerOpen.value = true }
function badge(count) { return count > 99 ? '99+' : count }
const menuBadges = computed(() => {
  const result = {}
  for (const category of workbench.summary?.categories || []) {
    if (category.kind === 'CHAT') continue
    const path = taskPaths[category.kind]?.split('?')[0]
    if (path && category.unread) result[path] = (result[path] || 0) + category.unread
  }
  return result
})
watch(() => route.fullPath, () => workbench.changed())
watch(() => [chatStore.newMessage, chatStore.connected, chatStore.badgeSignal], () => workbench.changed())
const siteStore = useSiteStore()

const isCollapse = ref(false)
const main = ref(null)
const pageVersion = ref(0)
function reloadPage() { loadingFeedback.readError = null; pageVersion.value++ }
onErrorCaptured(error => { if (error?.requestHandled) return false })
const navigationOpen = ref(false)
const { isCompact } = useResponsive()
function toggleNavigation() {
  if (isCompact.value) navigationOpen.value = !navigationOpen.value
  else isCollapse.value = !isCollapse.value
}
watch(() => route.fullPath, () => { navigationOpen.value = false })
watch(() => route.path, async () => {
  await nextTick()
  const element = main.value?.$el
  if (element) { element.scrollTop = 0; element.scrollLeft = 0; element.focus({ preventScroll: true }) }
})
watch(isCompact, compact => { if (!compact) navigationOpen.value = false })
const nickname = computed(() => userStore.nickname)
const role = computed(() => userStore.role)
const logoInitial = computed(() => {
  const title = siteStore.adminTitle || siteStore.siteName || '护'
  return title.trim().slice(0, 1)
})

const activeMenu = computed(() => route.meta.activeMenu || route.path)
const breadcrumbs = computed(() => route.matched.filter((r) => r.meta?.title))
const currentPageTitle = computed(() => {
  if (route.path === '/dashboard') return role.value === 'admin' ? '运营管理' : '客服工作台'
  return breadcrumbs.value[breadcrumbs.value.length - 1]?.meta?.title || '工作台'
})

const menuGroups = computed(() => {
  void enabledPlatformFeatures.value
  return buildNavigation(router.options.routes, role.value, can, platformFeatureAllowed)
})

const profileDialogVisible = ref(false)
const passwordDialogVisible = ref(false)

async function handleCommand(cmd) {
  if (cmd === 'profile') {
    profileDialogVisible.value = true
  } else if (cmd === 'password') {
    passwordDialogVisible.value = true
  } else if (cmd === 'logout') {
    chatStore.stop()
    await userStore.logout()
    router.push('/login')
  }
}

async function onPasswordChanged() {
  chatStore.stop()
    await userStore.logout()
  router.replace('/login')
}

function onProfileSaved(data) {
  userStore.updateProfile(data)
}

function goChat() {
  router.push('/chat/service')
}

onMounted(() => {
  workbench.start()
  if (role.value === 'admin' && ['system.manage', 'user.manage', 'finance.read'].some(can)) loadPlatformFeatureVisibility()
  if (can('chat.manage')) chatStore.start()
})
onUnmounted(() => {
  workbench.stop()
  chatStore.stop()
})
</script>

<style lang="scss" scoped>
.layout-container {
  height: var(--app-viewport-height);
  min-height: 0;
  background: #f5f6f7;
}
.layout-body { min-width: 0; min-height: 0; }
.layout-aside {
  background: #17191c;
  box-shadow: 1px 0 0 rgba(23, 25, 28, 0.08);
  transition: width 0.24s ease;
  overflow: hidden;
}
.layout-header {
  height: 64px;
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid #eceef0;
  box-shadow: none;
  backdrop-filter: blur(10px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  .header-left {
    display: flex;
    align-items: center;
    gap: 16px;
    .collapse-btn {
      width: 34px;
      height: 34px;
      border: 1px solid #e6e7e9;
      border-radius: 8px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      color: #555b63;
      background: #fff;
      cursor: pointer;
      transition: all 0.18s ease;
      &:hover {
        color: #17191c;
        border-color: #d8dadd;
        background: #f4f5f6;
      }
      svg {
        width: 18px;
        height: 18px;
      }
    }
    :deep(.el-breadcrumb__inner) {
      color: #666b73;
      font-weight: 500;
    }
    :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
      color: #17191c;
      font-weight: 700;
    }
  }
  .header-right {
    display: flex;
    align-items: center;
    gap: 12px;
    .chat-notify {
      position: relative;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 34px;
      height: 34px;
      border: 1px solid #e6e7e9;
      border-radius: 8px;
      background: #fff;
      transition: background 0.2s;
      color: #555b63;
      &:hover {
        background: #f4f5f6;
        color: #17191c;
      }
      .notify-badge.task-badge { background: #8c531a; }
      .notify-badge.chat-badge { background: #315b97; }
      .notify-badge {
        position: absolute;
        top: -4px;
        right: -8px;
        min-width: 16px;
        height: 16px;
        line-height: 16px;
        padding: 0 4px;
        font-size: 10px;
        color: #fff;
        background: #c94f59;
        border-radius: 10px;
        text-align: center;
      }
    }
    .role-tag {
      margin-right: 4px;
      color: #555b63;
      border-color: #e6e7e9;
      background: #f5f6f7;
    }
    .user-info {
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 6px;
      min-height: 34px;
      padding: 0 10px 0 6px;
      border-radius: 999px;
      color: #17191c;
      background: #f7f8f9;
      border: 1px solid #e6e7e9;
    }
  }
}
.layout-main {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background:
    linear-gradient(180deg, rgba(245, 246, 247, 0.82) 0%, rgba(245, 246, 247, 0) 210px),
    #f5f6f7;
  padding: 22px 24px 28px;
}
.mobile-page-title { display: none; }
.user-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.navigation-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  color: #f7f8f9;
  background: transparent;
  border: 1px solid #555b63;
  border-radius: 8px;
  cursor: pointer;
}
@media (max-width: 1024px) {
  .layout-header {
    height: auto;
    min-height: calc(56px + var(--app-safe-top));
    padding: calc(6px + var(--app-safe-top)) calc(12px + var(--app-safe-right)) 6px calc(12px + var(--app-safe-left));
    gap: 8px;
  }
  .layout-header .header-left { min-width: 0; gap: 10px; flex: 1; }
  .header-left :deep(.el-breadcrumb) { min-width: 0; font-size: 13px; line-height: 1.5; }
  .header-left :deep(.el-breadcrumb__item) { float: none; display: inline; overflow-wrap: anywhere; }
  .layout-header .header-left .collapse-btn { width: 40px; height: 40px; flex-shrink: 0; }
  .layout-header .header-right { min-width: 0; gap: 6px; }
  .header-right :deep(.el-dropdown) { min-width: 0; max-width: min(160px, 38vw); }
  .layout-header .header-right .chat-notify { width: 38px; height: 38px; flex-shrink: 0; }
  .layout-header .header-right .role-tag { margin-right: 0; }
  .layout-header .header-right .user-info { min-height: 38px; max-width: 150px; padding: 0 6px; }
  .layout-header .header-right .user-info { min-width: 0; }
  .user-info :deep(.el-avatar), .user-info :deep(.el-icon) { flex-shrink: 0; }
  .layout-main {
    padding: 12px calc(12px + var(--app-safe-right)) calc(82px + var(--app-safe-bottom)) calc(12px + var(--app-safe-left));
  }
  .mobile-bottom-nav {
    position: fixed;
    z-index: 80;
    left: 0;
    right: 0;
    bottom: 0;
    height: calc(62px + var(--app-safe-bottom));
    padding: 5px max(8px, var(--app-safe-right)) var(--app-safe-bottom) max(8px, var(--app-safe-left));
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    border-top: 1px solid #e7e9ec;
    background: rgba(255,255,255,.97);
    box-shadow: 0 -8px 28px rgba(23,25,28,.08);
    backdrop-filter: blur(14px);
    box-sizing: border-box;
  }
  .mobile-bottom-nav button {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
    border: 0;
    border-radius: 10px;
    background: transparent;
    color: #858b93;
    cursor: pointer;
  }
  .mobile-bottom-nav button.active { color: #17191c; background: transparent; }
  .mobile-bottom-nav .el-icon { font-size: 21px; }
  .mobile-bottom-nav span { font-size: 10px; font-weight: 650; }
  .mobile-bottom-nav .nav-unread { position:absolute; top:1px; left:calc(50% + 8px); min-width:16px; height:16px; padding:0 4px; border-radius:999px; background:#c94f59; color:#fff; font:10px/16px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; text-align:center; box-sizing:border-box; }
}
@media (max-width: 480px) {
  .layout-header { flex-wrap: nowrap; }
  .layout-header .header-left { flex-basis: auto; }
  .layout-header .header-left :deep(.el-breadcrumb) { display: none; }
  .layout-header .mobile-page-title { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #202327; font-size: 16px; font-weight: 750; }
  .layout-header .header-right { width: auto; justify-content: flex-end; margin-left: auto; }
  .layout-header .header-right .role-tag { display: none; }
  .layout-header .header-right .chat-notify { display: none; }
  .layout-header .header-right { gap: 0; }
  .layout-header .header-right .user-info { max-width: 38px; width: 38px; height: 38px; min-height: 38px; padding: 4px; justify-content: center; box-sizing: border-box; }
  .layout-header .header-right .user-name { display: none; }
  .layout-header .header-right .user-info > .el-icon { display: none; }
}
@media (max-width: 1024px) and (max-height: 500px) {
  .layout-header { flex-wrap: nowrap; min-height: 48px; }
  .layout-header .header-left { flex-basis: auto; }
  .layout-header .header-right { width: auto; }
  .layout-main { padding-top: 8px; padding-bottom: calc(70px + var(--app-safe-bottom)); }
  .mobile-bottom-nav { height: calc(52px + var(--app-safe-bottom)); }
  .mobile-bottom-nav span { display: none; }
}
</style>
