<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { McpToolPolicy, McpPolicyCatalog } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ projectId: string; serverId: string; catalog?: McpPolicyCatalog }>()
const emit = defineEmits<{ updated: [] }>()
const rows = ref<McpToolPolicy[]>([]), error = ref(''), loading = ref(false), saving = ref(false)
let generation = 0
const canDisableSource = computed(() => !props.serverId.startsWith('@loopper-') && rows.value.length > 0 && rows.value.every(row => row.configurable))
async function disableSource() {
  const current = generation; saving.value = true; error.value = ''
  try {
    await api.disableMcpSource({ projectId: props.projectId, serverId: props.serverId, tools: rows.value.map(row => ({ toolName: row.name, version: props.projectId ? row.projectVersion : row.globalVersion })) })
    if (current === generation) { if (props.catalog) emit('updated'); else await load() }
  } catch (cause) { if (current === generation) error.value = userFacingError(cause, '关闭失败，请刷新后重试；本次修改未生效') }
  finally { saving.value = false }
}
async function load() {
  const current = ++generation; loading.value = true; error.value = ''; rows.value = []
  try { const result = props.catalog ?? await api.getMcpToolPolicies(props.projectId, props.serverId); if (current === generation) { rows.value = result.tools; if (!result.complete) error.value = result.detail || '清单不完整，暂不能修改权限' } }
  catch (cause) { if (current === generation) error.value = userFacingError(cause, '工具策略读取失败，请重试') }
  finally { if (current === generation) loading.value = false }
}
async function update(row: McpToolPolicy, value: unknown) {
  const current = generation; saving.value = true; error.value = ''
  try { await api.updateMcpToolPolicy({ projectId: props.projectId, serverId: props.serverId, toolName: row.name, enabled: Number(value), version: props.projectId ? row.projectVersion : row.globalVersion }); if (current === generation) { if (props.catalog) emit('updated'); else await load() } }
  catch (cause) { if (current === generation) error.value = userFacingError(cause, '工具策略保存失败，请刷新后重试') }
  finally { saving.value = false }
}
watch(() => [props.projectId, props.serverId, props.catalog], () => { void load() }, { immediate: true })
</script>
<template>
  <div class="policies">
    <p v-if="loading" role="status">正在读取工具策略…</p><p v-if="error" role="alert">{{ error }} <el-button link @click="catalog ? emit('updated') : load()">刷新</el-button></p>
    <div v-if="canDisableSource" class="source-action"><el-button :loading="saving" :disabled="loading" @click="disableSource">关闭此来源全部工具</el-button><small>{{ projectId ? '仅当前项目的新会话生效' : '修改全局默认，项目单独启用的配置保留' }}</small></div>
    <div v-for="row in rows" :key="row.name" class="policy">
      <div><strong>{{ row.name }}</strong><p v-if="row.description" class="description">{{ row.description }}</p><p>{{ row.writes ? '写入任务产物' : serverId === '@loopper-assist' ? '只读' : '按角色授权调用' }} · {{ row.enabled ? '启用' : '停用' }} · {{ row.source === 'SYSTEM' ? '系统必需' : row.source === 'PROJECT' ? '项目配置' : '全局默认' }}</p></div>
      <span v-if="!row.configurable">{{ row.source === 'SYSTEM' ? '系统必需，不可关闭' : '清单不完整，不可配置' }}</span>
      <el-switch v-else-if="!projectId" :model-value="row.globalEnabled" :disabled="saving" :aria-label="`${row.name} 工具策略`" @change="(value: unknown) => update(row, value ? 1 : 0)" />
      <el-select v-else :model-value="projectId ? row.projectOverride === 'INHERIT' ? -1 : row.projectOverride === 'ENABLED' ? 1 : 0 : row.globalEnabled ? 1 : 0" :disabled="saving" :aria-label="`${row.name} 工具策略`" @change="(value: unknown) => update(row, value)"><el-option v-if="projectId" :value="-1" label="继承全局" /><el-option :value="1" label="启用" /><el-option :value="0" label="停用" /></el-select>
    </div>
  </div>
</template>
<style scoped>.source-action{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:12px}.source-action small{color:var(--color-text-secondary)}.policy{display:flex;align-items:center;justify-content:space-between;gap:16px;border-top:1px solid var(--color-border-default);padding:12px 0;flex-wrap:wrap}.policy strong{font:12px var(--font-code);overflow-wrap:anywhere}.policy p,.policy span{font-size:12px;color:var(--color-text-secondary)}.policy .el-select{width:150px}</style>

<style scoped>.policy>div{flex:1;min-width:200px}.description{white-space:pre-wrap;line-height:1.7;overflow-wrap:anywhere;max-width:80ch}</style>
