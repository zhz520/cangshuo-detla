<template>
  <Transition name="viewport" :appear="shell" @leave="removeImmediately">
    <div :key="viewportKey(path, shell)" class="page-viewport" :class="shell ? 'page-viewport--shell' : 'layout-page-frame'">
      <slot />
    </div>
  </Transition>
</template>

<script setup>
import { viewportKey } from '@/utils/page-viewport'
defineProps({ path: { type: String, required: true }, shell: Boolean })
// No outgoing interactive screen and no out-in blank period. A freshly
// mounted shell animates at the outer outlet, never a second time at its inner outlet.
function removeImmediately(_element, done) { done() }
</script>
