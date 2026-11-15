<template>
  <div class="h5-shell">
    <header class="h5-header" :class="{ 'has-back': showBack }">
      <button v-if="showBack" type="button" class="h5-back" aria-label="返回上一页" @click="goBack">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <div class="h5-heading">
        <span>{{ roleLabel }} · {{ siteStore.adminTitle || siteStore.siteName || '管理后台' }}</span>
        <h1>{{ currentPageTitle }}</h1>
      </div>
      <button type="button" class="h5-account" aria-label="打开账号菜单" @click="mobileAccountOpen = true">
        <StaffAvatar :size="34" :thumbnail-width="120" />
        <i v-if="workbench.summary?.unread" class="h5-account-badge">{{ badge(workbench.summary.unread) }}</i>
      </button>
    </header>

    <main ref="main" class="layout-main h5-main" tabindex="-1" :aria-busy="Boolean(loadingFeedback.destination) || loadingFeedback.requests > 0" aria-label="页面内容">
      <LoadFeedback :error="platformFeatureError" label="功能菜单" @retry="loadPlatformFeatureVisibility(true)" />
      <LoadFeedback v-if="loadingFeedback.readError?.path === route.fullPath" :error="loadingFeedback.readError.message" label="页面数据" retry-label="重新加载页面" @retry="reloadPage" />
      <router-view v-slot="{ Component, route: pageRoute }">
        <transition name="page-fade" mode="out-in">
          <div :key="`${pageRoute.path}:${pageVersion}`" class="layout-page-frame h5-page-frame">
            <component :is="Component" />
          </div>
        </transition>
      </router-view>
    </main>

    <nav class="h5-bottom-nav" :style="{ gridTemplateColumns: `repeat(${mobileBottomItems.length}, minmax(0, 1fr))` }" aria-label="H5 快捷导航">
      <button v-for="item in mobileBottomItems" :key="item.key" type="button" :class="{ active: mobileTabActive(item.key) }" :aria-current="mobileTabActive(item.key) ? 'page' : undefined" @click="runMobileTab(item.key)">
        <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
        <i v-if="item.badge" class="nav-unread">{{ badge(item.badge) }}</i>
      </button>
    </nav>

    <el-drawer v-model="mobileAccountOpen" direction="rtl" size="min(86vw, 360px)" :with-header="false" append-to-body>
      <div class="mobile-account">
        <div class="mobile-account-head">
          <StaffAvatar :size="52" :thumbnail-width="160" />
          <div><strong>{{ nickname }}</strong><span>{{ roleLabel }} · {{ userStore.phone || '未填写手机号' }}</span></div>
          <button type="button" aria-label="关闭" @click="mobileAccountOpen = false">×</button>
        </div>
        <div class="mobile-account-list">
          <button type="button" @click="accountAction('wechat')"><span>公众号消息通知</span><small>绑定当前账号的微信</small><b>›</b></button>
          <button type="button" @click="accountAction('notices')"><span>系统通知</span><small>查看审核、订单和售后消息</small><b>›</b></button>
          <button type="button" @click="openPc"><span>电脑版</span><small>返回 PC 后台</small><b>›</b></button>
          <button type="button" @click="accountAction('profile')"><span>编辑资料</span><b>›</b></button>
          <button type="button" @click="accountAction('password')"><span>修改密码</span><b>›</b></button>
          <button type="button" class="danger" @click="accountAction('logout')"><span>退出登录</span><b>›</b></button>
        </div>
        <p class="mobile-account-foot">H5 工作台 · {{ siteStore.siteName || siteStore.adminTitle || 'Delta' }}</p>
      </div>
    </el-drawer>

    <ProfileEditDialog v-model="profileDialogVisible" @saved="onProfileSaved" />
    <WorkbenchCenter v-model="centerOpen" :mode="centerMode" />
    <PasswordChangeDialog v-model="passwordDialogVisible" @changed="onPasswordChanged" />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick, onErrorCaptured } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Bell, ChatDotRound, HomeFilled, Tickets, UserFilled } from '@element-plus/icons-vue'
import { can } from '@/utils/staff-permissions'
import { loadPlatformFeatureVisibility, platformFeatureError } from '@/utils/platform-feature-visibility'
import { loadingFeedback } from '@/utils/loading-feedback'
import { installMobileTableCards } from '@/utils/mobile-table-cards'
import { canonicalAdminPath, h5Path } from '@/utils/h5-route'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useWorkbenchStore } from '@/stores/workbench'
import { useSiteStore } from '@/stores/site'
import LoadFeedback from '@/components/LoadFeedback.vue'
import ProfileEditDialog from '@/components/ProfileEditDialog.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'
import WorkbenchCenter from '@/components/WorkbenchCenter.vue'
import StaffAvatar from '@/components/StaffAvatar.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()
const workbench = useWorkbenchStore()
const siteStore = useSiteStore()

const main = ref(null)
const pageVersion = ref(0)
const centerOpen = ref(false)
const centerMode = ref('tasks')
const mobileAccountOpen = ref(false)
const profileDialogVisible = ref(false)
const passwordDialogVisible = ref(false)
const nickname = computed(() => userStore.nickname || '工作人员')
const role = computed(() => userStore.role)
const roleLabel = computed(() => role.value === 'admin' ? '管理员' : '客服')
const currentAdminPath = computed(() => canonicalAdminPath(route.path))
const currentPageTitle = computed(() => {
  if (currentAdminPath.value === '/dashboard') return role.value === 'admin' ? '运营管理' : '客服工作台'
  return [...route.matched].reverse().find(item => item.meta?.title)?.meta.title || '工作台'
})
const showBack = computed(() => currentAdminPath.value !== '/dashboard')

function badge(count) { return Number(count) > 99 ? '99+' : String(count) }
function openCenter(mode) { centerMode.value = mode; centerOpen.value = true }
function adminPath(path) { return h5Path(path) }
function goBack() {
  if (window.history.state?.back) router.back()
  else router.replace('/h5/dashboard')
}
function reloadPage() { loadingFeedback.readError = null; pageVersion.value++ }
onErrorCaptured(error => { if (error?.requestHandled) return false })

const mobileBottomItems = computed(() => {
  const items = [{ key: 'dashboard', label: '工作台', icon: HomeFilled }]
  if (can('order.read')) items.push({ key: 'orders', label: '订单', icon: Tickets })
  items.push({ key: 'tasks', label: '待办', icon: Bell, badge: workbench.summary?.unread || 0 })
  items.push({ key: 'messages', label: '消息', icon: ChatDotRound, badge: can('chat.manage') ? (workbench.summary?.chatUnread || 0) : (workbench.summary?.systemUnread || 0) })
  items.push({ key: 'account', label: '我的', icon: UserFilled })
  return items
})
function mobileTabActive(key) {
  if (key === 'dashboard') return currentAdminPath.value === '/dashboard'
  if (key === 'orders') return currentAdminPath.value.startsWith('/order')
  if (key === 'messages') return currentAdminPath.value.startsWith('/chat') || (centerOpen.value && centerMode.value === 'notices')
  if (key === 'tasks') return centerOpen.value && centerMode.value === 'tasks'
  if (key === 'account') return mobileAccountOpen.value
  return false
}
function runMobileTab(key) {
  if (key === 'dashboard') router.push(adminPath('/dashboard'))
  else if (key === 'orders') router.push(adminPath('/order/list'))
  else if (key === 'tasks') openCenter('tasks')
  else if (key === 'messages') can('chat.manage') ? router.push(adminPath('/chat/service')) : openCenter('notices')
  else if (key === 'account') mobileAccountOpen.value = true
}
function openPc() { window.location.assign('/') }

async function handleCommand(command) {
  if (command === 'wechat') router.push('/h5/wechat-binding')
  else if (command === 'profile') profileDialogVisible.value = true
  else if (command === 'password') passwordDialogVisible.value = true
  else if (command === 'logout') {
    chatStore.stop()
    await userStore.logout()
    router.replace('/h5/login')
  }
}
async function accountAction(command) {
  if (command === 'notices') {
    mobileAccountOpen.value = false
    openCenter('notices')
    return
  }
  mobileAccountOpen.value = false
  await handleCommand(command)
}
async function onPasswordChanged() {
  chatStore.stop()
  await userStore.logout()
  router.replace('/h5/login')
}
function onProfileSaved(data) { userStore.updateProfile(data) }

watch(() => route.fullPath, () => workbench.changed())
watch(() => [chatStore.newMessage, chatStore.connected, chatStore.badgeSignal], () => workbench.changed())
watch(() => route.path, async () => {
  await nextTick()
  if (main.value) { main.value.scrollTop = 0; main.value.scrollLeft = 0; main.value.focus({ preventScroll: true }) }
})

let stopMobileTables = () => {}
onMounted(() => {
  document.documentElement.dataset.adminShell = 'h5'
  workbench.start()
  if (role.value === 'admin' && ['system.manage', 'user.manage', 'finance.read'].some(can)) loadPlatformFeatureVisibility()
  if (can('chat.manage')) chatStore.start()
  nextTick(() => { stopMobileTables = installMobileTableCards(main.value) })
})
onUnmounted(() => {
  delete document.documentElement.dataset.adminShell
  stopMobileTables()
  workbench.stop()
  chatStore.stop()
})
</script>

<style lang="scss" scoped>
.h5-shell {
  display: flex;
  flex-direction: column;
  height: var(--app-viewport-height);
  min-height: 0;
  overflow: hidden;
  background: #f7f7f7;
  color: #111111;
}
.h5-header {
  position: relative;
  z-index: 20;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  flex: none;
  gap: 10px;
  min-height: calc(58px + var(--app-safe-top));
  padding: calc(7px + var(--app-safe-top)) calc(12px + var(--app-safe-right)) 7px calc(12px + var(--app-safe-left));
  border-bottom: 1px solid rgba(23, 25, 28, .07);
  background: rgba(255, 255, 255, .97);
  box-shadow: 0 8px 24px rgba(23, 25, 28, .035);
}
.h5-header.has-back { grid-template-columns: auto minmax(0, 1fr) auto; }
.h5-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: 1px solid #eeeeee;
  border-radius: 12px;
  background: #fff;
  color: #30353b;
}
.h5-heading { min-width: 0; }
.h5-heading span {
  display: block;
  overflow: hidden;
  color: #979da5;
  font-size: 9px;
  font-weight: 650;
  letter-spacing: .04em;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.h5-heading h1 {
  margin: 2px 0 0;
  overflow: hidden;
  color: #111111;
  font-size: 18px;
  font-weight: 800;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.h5-account {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 3px;
  border: 1px solid #eeeeee;
  border-radius: 50%;
  background: #f7f8f9;
}
.h5-account-badge {
  position: absolute;
  top: -3px;
  right: -2px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border: 2px solid #fff;
  border-radius: 999px;
  background: #c94f59;
  color: #fff;
  font: 9px/13px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  text-align: center;
  box-sizing: border-box;
}
.h5-main {
  flex: 1 1 auto;
  width: 100%;
  min-height: 0;
  margin: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 10px calc(12px + var(--app-safe-right)) calc(78px + var(--app-safe-bottom)) calc(12px + var(--app-safe-left));
  background: #f7f7f7;
  overscroll-behavior-y: contain;
}
.h5-page-frame { width: 100%; max-width: 100%; min-width: 0; }
.h5-bottom-nav {
  position: fixed;
  z-index: 80;
  right: 0;
  bottom: 0;
  left: 0;
  display: grid;
  height: calc(62px + var(--app-safe-bottom));
  padding: 5px max(8px, var(--app-safe-right)) var(--app-safe-bottom) max(8px, var(--app-safe-left));
  border-top: 1px solid #e7e9ec;
  background: rgba(255, 255, 255, .98);
  box-shadow: 0 -8px 28px rgba(23, 25, 28, .07);
  backdrop-filter: blur(14px);
  box-sizing: border-box;
}
.h5-bottom-nav button {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  min-width: 0;
  min-height: 44px;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: #858b93;
  cursor: pointer;
}
.h5-bottom-nav button.active { color: #111111; background: transparent; }
.h5-bottom-nav button.active .el-icon { transform: translateY(-1px); }
.h5-bottom-nav .el-icon { font-size: 21px; }
.h5-bottom-nav span { font-size: 10px; font-weight: 700; }
.h5-bottom-nav .nav-unread {
  position: absolute;
  top: 1px;
  left: calc(50% + 8px);
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: #c94f59;
  color: #fff;
  font: 10px/16px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  text-align: center;
  box-sizing: border-box;
}
.mobile-account { min-height: 100%; padding: 22px 18px; box-sizing: border-box; background: #f7f8f9; }
.mobile-account-head { display: flex; align-items: center; gap: 12px; padding: 18px; border: 1px solid #e8eaee; border-radius: 16px; background: #fff; }
.mobile-account-head > div { display: grid; flex: 1; min-width: 0; gap: 5px; }
.mobile-account-head strong { color: #20242a; font-size: 17px; }
.mobile-account-head span { color: #aaaaaa; font-size: 11px; }
.mobile-account-head button { width: 32px; height: 32px; border: 0; border-radius: 50%; background: #f1f3f5; color: #737b85; font-size: 20px; }
.mobile-account-list { display: grid; gap: 9px; margin-top: 14px; }
.mobile-account-list button { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 4px 10px; padding: 14px 15px; border: 1px solid #e8eaee; border-radius: 13px; background: #fff; color: #242931; text-align: left; }
.mobile-account-list span { font-size: 13px; font-weight: 650; }
.mobile-account-list small { grid-row: 2; color: #949aa2; font-size: 10px; }
.mobile-account-list b { grid-column: 2; grid-row: 1 / span 2; color: #a0a6ae; font-size: 20px; font-weight: 400; }
.mobile-account-list button.danger span { color: #b84851; }
.mobile-account-foot { margin: 22px 0 0; color: #a3a9b1; font-size: 10px; text-align: center; }

:global(html[data-admin-shell="h5"]) { background: #f7f7f7; }
:global(html[data-admin-shell="h5"] .h5-main .error-page) { min-height: calc(var(--app-viewport-height) - 170px); }
:global(html[data-admin-shell="h5"] .el-overlay) { z-index: 2200; }
:global(html[data-keyboard-open] .h5-bottom-nav) { display: none; }
:global(html[data-keyboard-open] .h5-main) { padding-bottom: calc(12px + var(--app-safe-bottom)); }

@media (max-width: 360px) {
  .h5-heading h1 { font-size: 16px; }
  .h5-main { padding-top: 8px; }
}
@media (max-width: 1024px) and (max-height: 500px) {
  .h5-header { min-height: calc(48px + var(--app-safe-top)); }
  .h5-main { padding-top: 8px; padding-bottom: calc(68px + var(--app-safe-bottom)); }
  .h5-bottom-nav { height: calc(52px + var(--app-safe-bottom)); }
  .h5-bottom-nav span { display: none; }
}
</style>
