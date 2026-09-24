<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { RoleSlotBinding } from '@/types/domain'
import { roleWorkflow } from '@/utils/roleWorkflow'
const props = defineProps<{ bindings: RoleSlotBinding[]; latestRevisionId: string }>()
defineEmits<{ revision: [id: string] }>()
const workflows = computed(() => {
  const groups = new Map<string, { name: string; steps: ReturnType<typeof roleWorkflow>['steps']; bindings: RoleSlotBinding[] }>()
  for (const binding of props.bindings) {
    const flow = roleWorkflow(binding.slot)
    const key = JSON.stringify(flow)
    const group = groups.get(key) ?? { ...flow, bindings: [] }
    group.bindings.push(binding); groups.set(key, group)
  }
  return [...groups.values()]
})
</script>

<template>
  <div class="workflow-diagrams">
    <section v-for="(flow, index) in workflows" :key="index" class="workflow-card" :aria-label="`${flow.name}流程图`">
      <header><span><Icon icon="lucide:workflow" width="17" />{{ flow.name }}</span><small><i />当前角色负责</small></header>
      <ol class="workflow-track">
        <li v-for="(step, stepIndex) in flow.steps" :key="stepIndex" :class="{ current: step.current }" :aria-label="`${step.label}${step.current ? '，当前角色负责' : ''}`">
          <span class="step-number">{{ String(stepIndex + 1).padStart(2, '0') }}</span><strong>{{ step.label }}</strong><Icon v-if="stepIndex < flow.steps.length - 1" class="flow-arrow" icon="lucide:arrow-right" width="17" aria-hidden="true" />
        </li>
      </ol>
      <ul class="slot-list"><li v-for="binding in flow.bindings" :key="binding.slot"><span>{{ binding.label || '角色阶段' }}</span><small>{{ binding.activeRevisionId === latestRevisionId ? '使用最新发布版本' : '使用其他已发布版本' }}</small><button type="button" class="inline-button" @click="$emit('revision', binding.activeRevisionId)">查看此阶段使用的版本</button></li></ul>
    </section>
  </div>
</template>

<style scoped>
.workflow-diagrams{display:grid;gap:16px}.workflow-card{border:1px solid var(--color-border-default);border-radius:var(--radius-card);padding:20px;background:var(--color-bg-surface)}
.workflow-card header{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-bottom:22px}.workflow-card header>span{display:flex;gap:8px;align-items:center;font-weight:600;font-size:14px}.workflow-card header small{display:flex;gap:6px;align-items:center;color:var(--color-text-secondary);font-size:11px}.workflow-card header i{width:7px;height:7px;border-radius:50%;background:var(--color-accent-cyan)}
.workflow-track{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px;padding:0;list-style:none;margin:0 0 22px}.workflow-track li{position:relative;display:flex;flex-direction:column;gap:10px;padding:16px 12px;border:1px solid var(--color-border-default);border-radius:var(--radius-control);background:var(--color-bg-elevated);min-width:0;color:var(--color-text-secondary)}.workflow-track li.current{border-color:var(--color-accent-cyan);background:color-mix(in srgb,var(--color-accent-cyan) 7%,var(--color-bg-surface));color:var(--color-text-primary)}.step-number{font:12px var(--font-code);color:var(--color-text-muted)}.current .step-number{color:var(--color-accent-cyan)}.workflow-track strong{font-size:13px;line-height:1.6;overflow-wrap:anywhere}.flow-arrow{position:absolute;right:-21px;top:calc(50% - 8px);color:var(--color-text-muted)}
.slot-list{list-style:none;margin:0;padding:14px 0 0;border-top:1px solid var(--color-border-default);display:grid;gap:10px}.slot-list li{display:flex;align-items:center;flex-wrap:wrap;gap:6px 12px;font-size:12px}.slot-list small{color:var(--color-text-secondary)}.inline-button{font:inherit;color:var(--color-accent-cyan);border:0;background:none;cursor:pointer;margin-left:auto;text-decoration:underline}.inline-button:focus-visible{outline:2px solid var(--color-accent-cyan);outline-offset:3px}
@container role-detail (max-width:500px){.workflow-track{grid-template-columns:1fr;gap:20px}.workflow-track li{flex-direction:row;align-items:center}.flow-arrow{right:auto;left:calc(50% - 8px);top:auto;bottom:-19px;transform:rotate(90deg)}.workflow-card header{flex-wrap:wrap}}
</style>
