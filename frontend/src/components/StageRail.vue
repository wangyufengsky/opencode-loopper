<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { Stage } from '@/types/domain'
import { rolePackLabel, statusLabel, testPolicyLabel } from '@/utils/displayLabels'

const props = defineProps<{ stages: Stage[] }>()

const sequenceStyle = computed(() => ({
  '--stage-count': props.stages.length,
  minWidth: `${props.stages.length * 280}px`,
}))

function connectorTone(index: number) {
  const current = props.stages[index]
  const next = props.stages[index + 1]
  if (current?.status === 'SUCCEEDED' && next?.status === 'SUCCEEDED') return 'connector-complete'
  if (current?.status === 'SUCCEEDED' && next?.status === 'RUNNING') return 'connector-active'
  return 'connector-pending'
}

function stageIcon(status: Stage['status']) {
  if (status === 'SUCCEEDED') return 'lucide:check'
  if (status === 'RUNNING') return 'lucide:radio-tower'
  if (status === 'FAILED') return 'lucide:triangle-alert'
  if (status === 'CANCELLED') return 'lucide:circle-x'
  if (status === 'PAUSED') return 'lucide:pause'
  return 'lucide:circle-dashed'
}

</script>

<template>
  <div class="stage-map" aria-label="阶段进度">
    <div class="stage-sequence" :style="sequenceStyle">
      <div class="stage-circuit" aria-hidden="true">
        <div v-for="(stage, index) in stages" :key="`node-${stage.id}`" class="stage-point">
          <span v-if="index < stages.length - 1" :class="['stage-connector', connectorTone(index)]"><i /></span>
          <span :class="['stage-node', `is-${stage.status.toLowerCase()}`]">
            <Icon :icon="stageIcon(stage.status)" width="14" />
            <b>{{ stage.ordinal }}</b>
          </span>
        </div>
      </div>

      <div class="stage-card-grid">
        <article v-for="stage in stages" :key="stage.id" :class="['phase-card', `is-${stage.status.toLowerCase()}`]">
          <header>
            <div>
              <span class="phase-index">阶段 {{ String(stage.ordinal).padStart(2, '0') }}</span>
              <strong>阶段 {{ stage.ordinal }}</strong>
            </div>
            <span class="phase-status"><Icon :icon="stageIcon(stage.status)" width="13" />{{ statusLabel(stage.status) }}</span>
          </header>
          <div class="phase-objective">
            <span>阶段目标</span>
            <p>{{ stage.objective }}</p>
          </div>
          <footer>
            <span v-if="stage.rolePackId"><Icon icon="lucide:package-check" width="12" />{{ rolePackLabel(stage.rolePackId) }}<template v-if="stage.testPolicy"> · {{ testPolicyLabel(stage.testPolicy) }}</template></span>
            <span><Icon icon="lucide:rotate-cw" width="12" />{{ (stage.attemptCount ?? stage.attempts.length) ? `${stage.attemptCount ?? stage.attempts.length} 次尝试` : '尚未尝试' }}</span>
          </footer>
        </article>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stage-map { --stage-gap: 22px; position: relative; max-width: 100%; overflow-x: auto; padding: 2px 2px 5px; scrollbar-color: rgb(var(--shade-cyan-01-rgb) / 28%) transparent; }
.stage-sequence { width: 100%; }
.stage-circuit, .stage-card-grid { display: grid; grid-template-columns: repeat(var(--stage-count), minmax(0, 1fr)); gap: var(--stage-gap); }
.stage-circuit { height: 52px; align-items: center; padding: 0 18px; }
.stage-point { position: relative; display: grid; height: 52px; place-items: center; }
.stage-node { position: relative; z-index: 2; display: grid; width: 38px; height: 38px; place-items: center; border: 1px solid var(--color-border-default); border-radius: calc(var(--radius-control) + 6px); color: var(--color-text-muted); background: var(--appearance-stage-rail-stage-node-background); box-shadow: var(--appearance-stage-rail-stage-node-box-shadow); transform: rotate(45deg); }
.stage-node :deep(svg) { transform: rotate(-45deg); }
.stage-node b { position: absolute; right: -5px; bottom: -5px; display: grid; width: 17px; height: 17px; place-items: center; border: 2px solid var(--color-bg-surface); border-radius: 50%; color: var(--color-text-secondary); background: var(--shade-elevated-08); font: 700 8px/1 var(--font-code); transform: rotate(-45deg); }
.stage-node.is-succeeded { border-color: rgb(var(--shade-success-01-rgb) / 72%); color: var(--shade-success-04); background: var(--appearance-stage-rail-stage-node-is-succeeded-background); box-shadow: var(--appearance-stage-rail-stage-node-is-succeeded-box-shadow); }
.stage-node.is-running, .stage-node.is-verifying { border-color: rgb(var(--shade-cyan-01-rgb) / 78%); color: var(--shade-cyan-02); background: var(--appearance-stage-rail-stage-node-is-running-background); box-shadow: var(--appearance-stage-rail-stage-node-is-running-box-shadow); animation: node-pulse 2.2s ease-in-out infinite; }
.stage-node.is-blocked { border-color: rgb(var(--shade-border-01-rgb) / 75%); color: var(--shade-danger-02); }
.stage-node.is-paused { border-color: rgb(var(--shade-warning-02-rgb) / 72%); color: var(--shade-warning-03); }
.stage-connector { position: absolute; z-index: 1; top: 25px; left: calc(50% + 19px); width: calc(100% + var(--stage-gap) - 38px); height: 2px; overflow: hidden; background: var(--shade-elevated-09); }
.stage-connector::before { position: absolute; inset: 0; background: currentcolor; content: ""; }
.stage-connector i { position: absolute; z-index: 1; top: -2px; width: 28px; height: 6px; background: var(--appearance-stage-rail-stage-connector-i-background); filter: blur(1px); opacity: 0; }
.connector-complete { color: var(--color-success); box-shadow: var(--appearance-stage-rail-connector-complete-box-shadow); }
.connector-active { color: var(--color-accent-cyan); }
.connector-active i { opacity: 1; animation: data-flow 2s linear infinite; }
.connector-pending { color: var(--shade-onEmphasis-01); }
.stage-card-grid { align-items: stretch; }
.phase-card { position: relative; display: grid; min-width: 0; grid-template-rows: auto 1fr auto; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: calc(var(--radius-control) + 6px); background: var(--appearance-stage-rail-phase-card-background); box-shadow: var(--appearance-stage-rail-phase-card-box-shadow); }
.phase-card::before { position: absolute; inset: 0; background-image: var(--appearance-stage-rail-phase-card-before-background-image); background-size: 18px 18px; content: ""; pointer-events: none; mask-image: linear-gradient(to bottom right, var(--shade-shadow-01), transparent 68%); }
.phase-card > * { position: relative; z-index: 1; }
.phase-card header { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 14px 15px; border-bottom: 1px solid rgb(var(--shade-secondary-05-rgb) / 12%); }
.phase-card header > div { display: grid; gap: 2px; }
.phase-index { color: var(--color-accent-cyan); font: 700 8px/1 var(--font-code); letter-spacing: .14em; }
.phase-card header strong { color: var(--shade-text-17); font-size: 13px; }
.phase-status { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 5px; padding: 5px 8px; border: 1px solid rgb(var(--shade-muted-01-rgb) / 28%); border-radius: 999px; color: var(--color-text-secondary); background: rgb(var(--shade-muted-01-rgb) / 8%); font-size: 9px; font-weight: 750; }
.phase-objective { padding: 17px 16px 19px; }
.phase-objective > span { display: block; margin-bottom: 8px; color: var(--color-text-muted); font: 750 9px/1 var(--font-code); letter-spacing: .1em; }
.phase-objective p { margin: 0; color: var(--shade-text-18); font-size: 12px; line-height: 1.75; overflow-wrap: anywhere; }
.phase-card footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 15px; border-top: 1px solid rgb(var(--shade-secondary-05-rgb) / 10%); color: var(--color-text-muted); background: rgb(var(--shade-canvas-15-rgb) / 25%); font-size: 9px; }
.phase-card footer span { display: inline-flex; min-width: 0; align-items: center; gap: 6px; }
.phase-card footer span:last-child { flex: 0 0 auto; font-family: var(--font-code); }
.phase-card.is-succeeded { border-color: rgb(var(--shade-success-01-rgb) / 31%); }
.phase-card.is-succeeded .phase-status { border-color: rgb(var(--shade-success-01-rgb) / 33%); color: var(--shade-success-04); background: rgb(var(--shade-success-01-rgb) / 7%); }
.phase-card.is-running, .phase-card.is-verifying { border-color: rgb(var(--shade-cyan-01-rgb) / 38%); box-shadow: var(--appearance-stage-rail-phase-card-is-running-box-shadow); }
.phase-card.is-running .phase-status, .phase-card.is-verifying .phase-status { border-color: rgb(var(--shade-cyan-01-rgb) / 36%); color: var(--shade-cyan-02); background: rgb(var(--shade-cyan-01-rgb) / 8%); }
.phase-card.is-blocked { border-color: rgb(var(--shade-border-01-rgb) / 38%); }
.phase-card.is-blocked .phase-status { color: var(--shade-danger-02); }

@keyframes data-flow { from { left: -28px; } to { left: 100%; } }
@keyframes node-pulse { 0%, 100% { filter: brightness(.9); } 50% { filter: brightness(1.25); } }
@media (max-width: 760px) {
  .stage-map { --stage-gap: 14px; }
  .phase-card footer { align-items: flex-start; flex-direction: column; }
}
@media (prefers-reduced-motion: reduce) { .stage-node, .stage-connector i { animation: none !important; } }
</style>
