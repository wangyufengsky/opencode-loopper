<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { LoopVerifierSpec } from '@/types/domain'

const props = defineProps<{ source: string }>()

interface AcceptanceStage {
  objective: string
  verifiers: LoopVerifierSpec[]
  weak: boolean
}

const stages = computed<AcceptanceStage[]>(() => {
  try {
    const parsed = JSON.parse(props.source) as { stages?: Array<{ objective?: string, verifiers?: LoopVerifierSpec[] }> }
    if (!Array.isArray(parsed.stages)) return []
    return parsed.stages.map((stage, index) => {
      const verifiers = Array.isArray(stage.verifiers) ? stage.verifiers : []
      return {
        objective: stage.objective?.trim() || `Stage ${index + 1}`,
        verifiers,
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
  if (type === 'FILE_EXISTS') return `文件存在：${verifier.path || '缺少路径'}`
  if (type === 'FILE_NOT_EXISTS') return `文件不存在：${verifier.path || '缺少路径'}`
  if (type === 'GIT_DIFF') return 'Git 改动范围与删除策略'
  return type || '未知 verifier'
}

function typeLabel(verifier: LoopVerifierSpec) {
  const type = String(verifier.type).toUpperCase()
  if (type === 'PROCESS') return '命令验证'
  if (type === 'FILE_EXISTS') return '文件存在'
  if (type === 'FILE_NOT_EXISTS') return '文件不存在'
  if (type === 'GIT_DIFF') return 'Git 差异检查'
  return type || '未知验收器'
}
</script>

<template>
  <section class="acceptance-panel" aria-labelledby="execution-acceptance-title">
    <header>
      <span class="acceptance-icon"><Icon icon="lucide:badge-check" /></span>
      <div><strong id="execution-acceptance-title">实际执行验收</strong><p>以下验收规则会由执行端自动判定；Designer 文档必须与这里一致。</p></div>
    </header>
    <div v-if="stages.length" class="acceptance-stages">
      <article v-for="(stage, index) in stages" :key="`${index}-${stage.objective}`" :class="['acceptance-stage', { weak: stage.weak }]">
        <div class="stage-title"><span>阶段 {{ index + 1 }}</span><strong>{{ stage.objective }}</strong></div>
        <div class="verifier-list">
          <span v-for="(verifier, verifierIndex) in stage.verifiers" :key="verifierIndex" class="verifier-chip">
            <b>{{ typeLabel(verifier) }}</b>{{ label(verifier) }}
          </span>
        </div>
        <p v-if="stage.weak" class="weak-warning"><Icon icon="lucide:triangle-alert" />只有 Git 差异检查，无法证明 Designer 描述的功能验收；保存或确认会被拒绝。</p>
      </article>
    </div>
    <p v-else class="acceptance-empty">LoopSpec 数据有误或尚未定义执行阶段。</p>
  </section>
</template>

<style scoped>
.acceptance-panel { margin: 0 20px 14px; padding: 13px; border: 1px solid rgb(34 211 238 / 18%); border-radius: 10px; background: rgb(34 211 238 / 4%); }
.acceptance-panel > header { display: flex; align-items: flex-start; gap: 9px; }.acceptance-icon { display: grid; flex: 0 0 auto; place-items: center; width: 27px; height: 27px; border-radius: 7px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 9%); }.acceptance-panel header strong { color: var(--color-text-primary); font-size: 11px; }.acceptance-panel header p { margin: 3px 0 0; color: var(--color-text-muted); font-size: 9px; line-height: 1.5; }
.acceptance-stages { display: grid; gap: 8px; margin-top: 11px; }.acceptance-stage { padding: 9px; border: 1px solid var(--color-border-default); border-radius: 7px; background: rgb(7 11 20 / 45%); }.acceptance-stage.weak { border-color: rgb(245 158 11 / 28%); }.stage-title { display: flex; align-items: center; gap: 8px; min-width: 0; }.stage-title span { flex: 0 0 auto; color: var(--color-accent-ai); font: 750 8px/1 var(--font-code); }.stage-title strong { overflow: hidden; color: var(--color-text-secondary); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.verifier-list { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }.verifier-chip { display: inline-flex; align-items: center; gap: 5px; max-width: 100%; padding: 5px 7px; border-radius: 5px; color: var(--color-text-secondary); background: rgb(101 115 138 / 10%); font: 8px/1.35 var(--font-code); overflow-wrap: anywhere; }.verifier-chip b { color: var(--color-accent-cyan); }.weak-warning { display: flex; align-items: flex-start; gap: 5px; margin: 8px 0 0; color: var(--color-session-warning); font-size: 8px; line-height: 1.45; }.weak-warning svg { flex: 0 0 auto; }.acceptance-empty { margin: 10px 0 0 36px; color: var(--color-session-warning); font-size: 9px; }
</style>
