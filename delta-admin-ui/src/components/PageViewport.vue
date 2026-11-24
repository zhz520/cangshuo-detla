<template>
  <Transition name="viewport" :appear="shell" @leave="removeImmediately">
    <div :key="viewportKey(page, shell)" class="page-viewport" :data-nav-kind="state.kind"
      :class="[shell ? 'page-viewport--shell' : 'layout-page-frame', { 'viewport-departing': state.leaving === (shell ? 'shell' : 'page') }]">
      <slot />
    </div>
  </Transition>
</template>

<script setup>
import { viewportKey } from '@/utils/page-viewport'
import { navigationMotion } from '@/utils/navigation-motion'
const { state } = navigationMotion
defineProps({ page: { type: Object, required: true }, shell: Boolean })
// The brief departure runs on the still-current screen while resolving.
// Never retain a second interactive screen, including on rapid back/forward.
function removeImmediately(_element, done) { done() }
</script>
