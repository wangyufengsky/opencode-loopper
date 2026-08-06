<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import { api } from '@/api/client'
import type { TaskInsight, UsageAggregate } from '@/types/domain'

const tasks = ref<TaskInsight[]>([]); const globalUsage = ref<UsageAggregate>(); const loading = ref(true); const error = ref('')
const knownTasks = computed(() => tasks.value.filter(task => task.usage.totalTokens !== null).length)
const qualityMeta: Record<TaskInsight['quality']['state'], { label: string; icon: string }> = {
  PASS: { label: '质量通过', icon: 'lucide:shield-check' },
  REVIEW_REQUIRED: { label: '待评审', icon: 'lucide:scan-eye' },
  PENDING: { label: '待验收', icon: 'lucide:clock-3' },
}
function tokens(value: number | null) { return value === null ? '未知' : value.toLocaleString('zh-CN') }
function duration(value: number) { return value < 60_000 ? `${Math.round(value / 1000)} 秒` : `${Math.floor(value / 60_000)} 分 ${Math.round(value % 60_000 / 1000)} 秒` }
async function load() {
  loading.value = true; error.value = ''
  try {
    const payload = await api.getInsights()
    tasks.value = payload.tasks; globalUsage.value = payload.usage
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取服务端洞察' }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <PageHeader eyebrow="Insights / Evidence" title="用量与质量洞察">
    <template #actions><el-button :loading="loading" @click="load"><Icon icon="lucide:refresh-cw" />刷新</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="error" class="error-panel error-panel-task" role="status"><Icon class="error-panel-icon" icon="lucide:triangle-alert" /><div><h3>洞察未更新</h3><p>{{ error }}；页面不会用 0 替代未知 provider 数据。</p></div></section>
    <section v-else-if="loading" class="card empty-state"><div><Icon icon="lucide:loader-circle" class="spin" /><strong>正在读取持久化证据</strong><p>用量、验收与评审结果均以服务端数据为准。</p></div></section>
    <template v-else>
      <section class="insight-metrics" aria-label="全局用量"><article class="card"><span>可靠总 Tokens</span><strong>{{ tokens(globalUsage?.totalTokens ?? null) }}</strong><small>{{ globalUsage?.unknownUsageCount ?? 0 }} 条未知记录</small></article><article class="card"><span>有已知用量的任务</span><strong>{{ knownTasks }}</strong><small>不将缺失数据估为零</small></article><article class="card"><span>成本（按币种）</span><strong class="costs">{{ Object.entries(globalUsage?.costByCurrency ?? {}).map(([currency, amount]) => `${currency} ${amount}`).join(' · ') || '未知' }}</strong><small>不同币种绝不相加</small></article></section>
      <section v-if="!tasks.length" class="card empty-state"><div><Icon icon="lucide:chart-no-axes-combined" /><strong>尚无可汇总的任务证据</strong><p>完成一次 Session、确定性验收或双评审后，这里会显示服务端聚合。</p></div></section>
      <section v-else class="insight-list" aria-label="任务洞察"><article v-for="task in tasks" :key="task.taskId" class="card insight-row"><header><div><p class="eyebrow">{{ task.state }}</p><h2>{{ task.title }}</h2></div><span :class="['quality', `quality-${task.quality.state.toLowerCase()}`]" :title="task.quality.state"><span class="quality-icon" aria-hidden="true"><Icon :icon="qualityMeta[task.quality.state].icon" /></span><span>{{ qualityMeta[task.quality.state].label }}</span></span></header><dl><div><dt>Token</dt><dd>{{ tokens(task.usage.totalTokens) }}<small v-if="task.usage.unknownUsageCount"> + {{ task.usage.unknownUsageCount }} 未知</small></dd></div><div><dt>耗时</dt><dd>{{ duration(task.durationMs) }}</dd></div><div><dt>重试</dt><dd>{{ task.retryCount }}</dd></div><div><dt>质量</dt><dd>{{ task.quality.deterministicPassed ? '验收通过' : '验收待定' }} · {{ task.quality.requirementJudgePassed && task.quality.riskJudgePassed ? '双评审通过' : '评审待定' }}</dd></div></dl></article></section>
    </template>
  </main>
</template>

<style scoped>
.insight-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-bottom:16px}.insight-metrics article,.insight-row{padding:16px;background:linear-gradient(145deg,rgb(18 28 48 / 96%),rgb(8 14 28 / 92%))}.insight-metrics span,.insight-metrics small,dt{color:var(--color-text-muted);font:700 10px/1.4 var(--font-code)}.insight-metrics strong{display:block;margin:10px 0 5px;color:var(--color-text-primary);font-size:24px}.insight-metrics .costs{font-size:13px;line-height:1.55}.insight-list{display:grid;gap:12px}.insight-row header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.insight-row h2{margin:0;font-size:15px}.quality{--quality-accent:var(--color-session-warning);--quality-border:rgb(245 158 11 / 24%);--quality-surface:rgb(245 158 11 / 7%);display:inline-flex;align-items:center;flex:0 0 auto;gap:7px;min-height:28px;padding:3px 10px 3px 5px;border:1px solid var(--quality-border);border-radius:7px;color:var(--quality-accent);background:linear-gradient(135deg,var(--quality-surface),rgb(7 11 20 / 38%));box-shadow:inset 0 1px rgb(255 255 255 / 3%),0 5px 18px rgb(0 0 0 / 12%);font:650 10px/1 var(--font-ui);letter-spacing:.03em;white-space:nowrap}.quality-icon{display:grid;place-items:center;width:18px;height:18px;border:1px solid var(--quality-border);border-radius:5px;background:var(--quality-surface);font-size:12px}.quality-pass{--quality-accent:#5fd384;--quality-border:rgb(34 197 94 / 25%);--quality-surface:rgb(34 197 94 / 8%)}.quality-review_required{--quality-accent:#d6a74b;--quality-border:rgb(245 158 11 / 24%);--quality-surface:rgb(245 158 11 / 7%)}.quality-pending{--quality-accent:#8d9bb0;--quality-border:rgb(154 168 189 / 20%);--quality-surface:rgb(154 168 189 / 6%)}.insight-row dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:1px;margin:15px 0 0;background:var(--color-border-default)}.insight-row dl div{padding:10px;background:rgb(2 6 23 / 40%)}dd{margin:6px 0 0;color:var(--color-text-secondary);font-size:11px}dd small{color:var(--color-session-warning)}.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:800px){.insight-metrics,.insight-row dl{grid-template-columns:1fr}}
</style>
