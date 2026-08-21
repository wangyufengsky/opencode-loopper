<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'

const props = defineProps<{ totalTokens?: number | null }>()
const numberFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })
const previousTotal = ref<number>()
const delta = ref<number>()
const pulseKey = ref(0)
let pulseTimer: ReturnType<typeof setTimeout> | undefined

const formattedTotal = computed(() => previousTotal.value == null ? '—' : numberFormat.format(previousTotal.value))
const accessibleLabel = computed(() => previousTotal.value == null
  ? 'Token 用量暂不可用'
  : `累计 Token ${formattedTotal.value}`)

watch(() => props.totalTokens, (next) => {
  if (next == null) return
  const previous = previousTotal.value
  if (previous == null) {
    previousTotal.value = next
    return
  }
  if (next <= previous) return
  previousTotal.value = next
  delta.value = next - previous
  pulseKey.value += 1
  if (pulseTimer) clearTimeout(pulseTimer)
  const currentKey = pulseKey.value
  pulseTimer = setTimeout(() => {
    if (currentKey === pulseKey.value) delta.value = undefined
  }, 850)
}, { immediate: true })

onBeforeUnmount(() => {
  if (pulseTimer) clearTimeout(pulseTimer)
})
</script>

<template>
  <div class="token-usage-window" role="status" aria-live="polite" aria-atomic="true" :aria-label="accessibleLabel">
    <span class="token-icon" aria-hidden="true"><Icon icon="lucide:sparkles" width="14" /></span>
    <strong>{{ formattedTotal }}</strong>
    <Transition name="token-burst">
      <span v-if="delta" :key="pulseKey" class="token-delta" aria-hidden="true">+{{ numberFormat.format(delta) }}</span>
    </Transition>
  </div>
</template>

<style scoped>
.token-usage-window {
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  min-width: 92px;
  height: 34px;
  gap: 8px;
  padding: 0 12px;
  overflow: visible;
  border: 1px solid rgb(34 211 238 / 28%);
  border-radius: 10px;
  color: #dffaff;
  background: linear-gradient(135deg, rgb(34 211 238 / 11%), rgb(139 92 246 / 13%)), rgb(7 11 20 / 88%);
  box-shadow: inset 0 1px rgb(255 255 255 / 4%), 0 0 22px rgb(34 211 238 / 8%);
}
.token-usage-window::after {
  position: absolute;
  inset: auto 12px 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgb(34 211 238 / 70%), transparent);
  content: "";
}
.token-icon {
  display: grid;
  place-items: center;
  color: var(--color-accent-ai);
  filter: drop-shadow(0 0 7px rgb(139 92 246 / 55%));
}
.token-usage-window strong {
  min-width: 0;
  overflow: hidden;
  font: 750 11px/1 var(--font-code);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.token-delta {
  position: absolute;
  top: -9px;
  right: 8px;
  color: var(--color-accent-cyan);
  font: 800 10px/1 var(--font-code);
  font-variant-numeric: tabular-nums;
  pointer-events: none;
  text-shadow: 0 0 12px rgb(34 211 238 / 75%);
}
.token-burst-enter-active { animation: token-rise .8s cubic-bezier(.2, .8, .2, 1); }
.token-burst-leave-active { transition: opacity .08s linear; }
.token-burst-leave-to { opacity: 0; }
@keyframes token-rise {
  0% { opacity: 0; transform: translate3d(0, 7px, 0) scale(.86); }
  22% { opacity: 1; transform: translate3d(0, 0, 0) scale(1); }
  72% { opacity: 1; transform: translate3d(0, -5px, 0) scale(1); }
  100% { opacity: 0; transform: translate3d(0, -12px, 0) scale(.96); }
}
@media (prefers-reduced-motion: reduce) { .token-delta { display: none; } }
</style>
