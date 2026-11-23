<template>
  <div class="session-panel">
    <div class="panel-header">
      <span>客服会话</span>
      <el-button link type="primary" :disabled="loading" :loading="visible && sessions.length > 0" @click="$emit('refresh')">刷新</el-button>
    </div>
    <div class="session-list" :aria-busy="loading">
      <LoadFeedback :loading="loading && !sessions.length" :error="error" label="会话列表" @retry="$emit('refresh')" />
      <div
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ active: activeId === s.id }"
        role="button"
        tabindex="0"
        @click="$emit('select', s)"
        @keydown.enter.prevent="$emit('select', s)"
        @keydown.space.prevent="$emit('select', s)"
      >
        <div class="session-row">
          <el-avatar :src="thumbnailUrl(s.avatar || '/static/images/delta-default-avatar.png',160)" :size="38" class="session-avatar">{{ (s.targetName || '')[0] || '用' }}</el-avatar>
          <div class="session-body">
            <div class="session-top">
              <span class="session-name">{{ s.targetName || '用户 #' + s.id }}</span>
              <span class="session-time">{{ (s.lastMessageAt || '').replace('T', ' ').slice(0, 19) }}</span>
            </div>
            <div class="session-bottom">
              <span class="session-last-msg">{{ s.lastMessage || '暂无消息' }}</span>
              <span v-if="s.unreadCount > 0 && activeId !== s.id" class="unread-badge">{{ s.unreadCount > 99 ? '99+' : s.unreadCount }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-if="!loading && !error && sessions.length === 0" class="empty-tip">暂无会话</div>
    </div>
  </div>
</template>

<script setup>
import LoadFeedback from '@/components/LoadFeedback.vue'
import { thumbnailUrl } from '@/utils/image'
import { useDelayedBusy } from '@/composables/useDelayedBusy'
const props = defineProps({ sessions: { type: Array, required: true }, activeId: [Number, String], loading: Boolean, error: String })
defineEmits(['refresh', 'select'])
const visible = useDelayedBusy(() => props.loading)
</script>

<style scoped>
.session-panel {
  width: 280px;
  border-right: 1px solid var(--app-border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.panel-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--app-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  font-size: 15px;
}
.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.session-item {
  padding: 12px 16px;
  cursor: pointer;
  border-bottom: 1px solid var(--app-border-soft);
  transition: background var(--delta-motion-feedback) var(--delta-motion-ease);
}
.session-item:hover { background: var(--app-surface-soft); }
.session-item.active { background: var(--app-primary-soft); border-left: 3px solid var(--app-text); }
.session-row { display: flex; align-items: center; gap: 10px; width: 100%; }
.session-avatar { flex-shrink: 0; }
.session-body { flex: 1; overflow: hidden; }
.session-top { display: flex; justify-content: space-between; align-items: center; }
.session-name { font-size: 14px; font-weight: 500; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.session-time { font-size: 11px; color: var(--app-text-muted); flex-shrink: 0; margin-left: 8px; }
.session-bottom { display: flex; justify-content: space-between; align-items: center; margin-top: 4px; }
.session-last-msg { font-size: 12px; color: var(--app-text-regular); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.unread-badge {
  min-width: 18px; height: 18px; line-height: 18px; padding: 0 5px;
  font-size: 11px; color: #fff; background: #c94f59; border-radius: 10px;
  text-align: center; flex-shrink: 0; margin-left: 6px;
}

.empty-tip { text-align: center; padding: 20px; color: var(--app-text-muted); font-size: 13px; }
@media (max-width: 1024px) { .session-panel { width: 100%; border-right: 0; min-height: 0; } }
</style>
