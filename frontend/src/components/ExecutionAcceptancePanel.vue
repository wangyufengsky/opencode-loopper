<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { LoopSpec, LoopVerifierSpec } from '@/types/domain'
import { displayLabel } from '@/utils/displayLabels'

const props = defineProps<{ source: string }>()

interface AcceptanceStage {
  objective: string
  verifiers: LoopVerifierSpec[]
  criteria: NonNullable<LoopSpec['stages'][number]['acceptanceCriteria']>
  weak: boolean
}

const stages = computed<AcceptanceStage[]>(() => {
  try {
    const parsed = JSON.parse(props.source) as LoopSpec
    if (!Array.isArray(parsed.stages)) return []
    return parsed.stages.map((stage, index) => {
      const verifiers = Array.isArray(stage.verifiers) ? stage.verifiers : []
      return {
        objective: stage.objective?.trim() || `阶段 ${index + 1}`,
        verifiers,
        criteria: Array.isArray(stage.acceptanceCriteria) ? stage.acceptanceCriteria : [],
        weak: !verifiers.some((verifier) => String(verifier.type).toUpperCase() !== 'GIT_DIFF'),
      }
    })
  } catch { return [] }
})

function label(verifier: LoopVerifierSpec) {
  const type = String(verifier.type).toUpperCase()
  if (type === 'PROCESS') {
    const command = verifier.command?.join(' ') || '缺少命令'
    return verifier.outputContains ? `${command}  → 包含“${verifier.outputContains}”` : command
  }
  if (type === 'FILE_EXISTS') return `仅记录：${verifier.path || '缺少路径'}`
  if (type === 'FILE_NOT_EXISTS') return `文件不存在：${verifier.path || '缺少路径'}`
  if (type === 'GIT_DIFF') return 'Git 改动范围与删除策略'
  return displayLabel(type || 'UNKNOWN')
}

function typeLabel(verifier: LoopVerifierSpec) {
  const type = String(verifier.type).toUpperCase()
  if (type === 'PROCESS') return '命令验证'
  if (type === 'FILE_EXISTS') return '兼容检查（不阻断）'
  if (type === 'FILE_NOT_EXISTS') return '文件不存在'
  if (type === 'GIT_DIFF') return 'Git 差异检查'
  return displayLabel(type || 'UNKNOWN')
}

function modeLabel(mode?: string) {
  if (mode === 'JUDGE') return 'AI 评审'
  if (mode === 'BOTH') return '机器 + AI'
  return '机器验证'
}
</script>

<template>
  <section class="acceptance-panel" aria-labelledby="execution-acceptance-title">
    <header>
      <span class="acceptance-icon"><Icon icon="lucide:badge-check" /></span>
      <div><strong id="execution-acceptance-title">验收计划</strong></div>
    </header>
    <div v-if="stages.length" class="acceptance-stages">
      <article v-for="(stage, index) in stages" :key="`${index}-${stage.objective}`" :class="['acceptance-stage', { weak: stage.weak }]">
        <div class="stage-title"><span>阶段 {{ index + 1 }}</span><strong>{{ stage.objective }}</strong></div>
        <div class="acceptance-subtitle">机器执行验收</div>
        <div class="verifier-list">
          <span v-for="(verifier, verifierIndex) in stage.verifiers" :key="verifierIndex" class="verifier-chip">
            <b>{{ typeLabel(verifier) }}</b><span class="verifier-detail">{{ label(verifier) }}</span>
          </span>
        </div>
        <template v-if="stage.criteria.some(criterion => criterion.verificationMode === 'JUDGE' || criterion.verificationMode === 'BOTH')">
          <div class="acceptance-subtitle judge-title">最终 AI 评审</div>
          <div class="judge-list">
            <div v-for="criterion in stage.criteria.filter(item => item.verificationMode === 'JUDGE' || item.verificationMode === 'BOTH')" :key="criterion.id" class="judge-item">
              <span><b>{{ modeLabel(criterion.verificationMode) }}</b></span>
              <p>{{ criterion.judgeRubric || '尚未填写 AI 评审准则' }}</p>
              <small v-if="criterion.verificationMode === 'JUDGE'">仅 AI 原因：{{ criterion.judgeOnlyReason || '尚未填写' }}</small>
            </div>
          </div>
        </template>
        <p v-if="stage.weak" class="weak-warning"><Icon icon="lucide:triangle-alert" />缺少功能验收，暂时无法确认。</p>
      </article>
    </div>
    <p v-else class="acceptance-empty">执行规范有误或尚未定义阶段。</p>
  </section>
</template>

<style scoped>
.acceptance-panel { min-width: 0; max-width: 100%; margin: 0 20px 14px; padding: 13px; border: 1px solid rgb(34 211 238 / 18%); border-radius: 10px; background: rgb(34 211 238 / 4%); }
.acceptance-panel > header { display: flex; align-items: flex-start; gap: 9px; min-width: 0; }.acceptance-panel > header > div { min-width: 0; }.acceptance-icon { display: grid; flex: 0 0 auto; place-items: center; width: 27px; height: 27px; border-radius: 7px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 9%); }.acceptance-panel header strong { color: var(--color-text-primary); font-size: 11px; }.acceptance-panel header p { margin: 3px 0 0; color: var(--color-text-muted); font-size: 9px; line-height: 1.5; overflow-wrap: anywhere; }
.acceptance-stages { display: grid; min-width: 0; gap: 8px; margin-top: 11px; }.acceptance-stage { min-width: 0; padding: 9px; border: 1px solid var(--color-border-default); border-radius: 7px; background: rgb(7 11 20 / 45%); }.acceptance-stage.weak { border-color: rgb(245 158 11 / 28%); }.stage-title { display: flex; align-items: flex-start; gap: 8px; min-width: 0; }.stage-title span { flex: 0 0 auto; color: var(--color-accent-ai); font: 750 8px/1.5 var(--font-code); }.stage-title strong { min-width: 0; color: var(--color-text-secondary); font-size: 9px; line-height: 1.5; overflow-wrap: anywhere; white-space: normal; }.verifier-list { display: flex; flex-wrap: wrap; min-width: 0; gap: 5px; margin-top: 8px; }.verifier-chip { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; gap: 5px; max-width: 100%; padding: 5px 7px; border-radius: 5px; color: var(--color-text-secondary); background: rgb(101 115 138 / 10%); font: 8px/1.35 var(--font-code); }.verifier-chip b { color: var(--color-accent-cyan); }.verifier-detail { min-width: 0; overflow-wrap: anywhere; white-space: normal; }.weak-warning { display: flex; align-items: flex-start; gap: 5px; margin: 8px 0 0; color: var(--color-session-warning); font-size: 8px; line-height: 1.45; overflow-wrap: anywhere; }.weak-warning svg { flex: 0 0 auto; }.acceptance-empty { margin: 10px 0 0 36px; color: var(--color-session-warning); font-size: 9px; }
.acceptance-subtitle { margin-top: 9px; color: var(--color-text-muted); font: 700 8px/1.4 var(--font-code); text-transform: uppercase; }.judge-title { color: var(--color-accent-ai); }.judge-list { display: grid; gap: 5px; margin-top: 6px; }.judge-item { padding: 7px; border: 1px solid rgb(139 92 246 / 22%); border-radius: 6px; background: rgb(139 92 246 / 6%); }.judge-item > span { display: flex; gap: 7px; align-items: center; }.judge-item code { color: var(--color-accent-ai); font-size: 8px; }.judge-item b { color: var(--color-text-secondary); font-size: 8px; }.judge-item p, .judge-item small { display: block; margin: 4px 0 0; color: var(--color-text-muted); font-size: 8px; line-height: 1.45; overflow-wrap: anywhere; }
</style>
