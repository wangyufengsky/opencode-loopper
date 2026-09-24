<script setup lang="ts">
import { onBeforeUnmount, ref, useId } from 'vue'
import { api, ApiError } from '@/api/client'
import type { TaskSessionRoleSummary } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ taskId: string; sessionKey: string }>()
const contentId = useId()
const expanded = ref(false)
const loading = ref(false)
const error = ref('')
const summary = ref<TaskSessionRoleSummary | null>(null)
let requestId = 0

const permissionNames: Record<string, string> = {
  read: '读取文件', write: '写入文件', edit: '编辑文件', bash: '终端命令',
  apply_patch: '应用补丁', glob: '匹配文件', grep: '搜索内容', list: '列出文件',
  task: '子任务', webfetch: '读取网页', external_directory: '访问项目外目录',
}

function permissionLabel(permission: string): string {
  return permissionNames[permission] ?? '其他权限'
}

function actionLabel(action: string): string {
  return ({ allow: '允许', deny: '拒绝', ask: '需确认' } as Record<string, string>)[action] ?? '待核对'
}

function shortDigest(digest: string | null): string {
  return digest ? `${digest.slice(0, 12)}…` : '未记录'
}

async function load() {
  if (loading.value) return
  const currentRequest = ++requestId
  loading.value = true
  error.value = ''
  try {
    const result = await api.getTaskSessionRole(props.taskId, props.sessionKey)
    if (currentRequest === requestId) summary.value = result
  } catch (cause) {
    if (currentRequest === requestId) {
      error.value = cause instanceof ApiError && /^请求失败 \(\d+\)$/.test(cause.message)
        ? '会话角色摘要读取失败，请重试。'
        : userFacingError(cause, '会话角色摘要读取失败，请重试。')
    }
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

function toggle() {
  expanded.value = !expanded.value
  if (expanded.value && !summary.value && !error.value) void load()
}

onBeforeUnmount(() => { requestId++ })
</script>

<template>
  <section class="session-role" aria-label="会话角色与冻结权限">
    <button type="button" class="session-role-toggle" :aria-expanded="expanded" :aria-controls="contentId" @click="toggle">
      <span>会话角色与冻结权限</span><span aria-hidden="true">{{ expanded ? '收起 ↑' : '查看 ↓' }}</span>
    </button>
    <div v-if="expanded" :id="contentId" class="session-role-content">
      <p v-if="loading" role="status">正在读取会话冻结权限…</p>
      <p v-if="error" class="role-error" role="alert">{{ error }} <button type="button" class="retry-button" @click="load">重试</button></p>
      <p v-if="summary && !summary.configured" class="role-note">此会话创建时未记录角色配置快照，因此无法展示冻结角色版本。会话仍按创建时的权限合同执行。</p>
      <template v-if="summary?.configured">
        <p class="role-note">此处展示会话创建时冻结的角色版本和权限规则，不包含完整 Prompt 或临时凭证。</p>
        <div class="role-digests"><span>角色配置摘要 <strong>{{ shortDigest(summary.revisionSha256) }}</strong></span><span>权限摘要 <strong>{{ shortDigest(summary.permissionSha256) }}</strong></span></div>
        <h3>创建时的权限规则</h3>
        <p v-if="!summary.permissions.length" class="role-note">此会话没有记录可展示的权限规则。</p>
        <ol v-else class="permission-rules">
          <li v-for="(rule, index) in summary.permissions" :key="index">
            <span :class="['permission-action', rule.action]">{{ actionLabel(rule.action) }}</span>
            <strong>{{ permissionLabel(rule.permission) }}</strong>
            <code>{{ rule.pattern }}</code>
          </li>
        </ol>
        <details class="technical-details">
          <summary>查看技术标识</summary>
          <dl><dt>角色标识</dt><dd>{{ summary.roleId }}</dd><dt>修订标识</dt><dd>{{ summary.revisionId }}</dd><dt>流程阶段</dt><dd>{{ summary.slot }}</dd><dt>适配画像</dt><dd>{{ summary.adapterProfile }}</dd><dt>适配版本</dt><dd>{{ summary.adapterVersion }}</dd><dt>角色配置完整摘要</dt><dd>{{ summary.revisionSha256 }}</dd><dt>权限完整摘要</dt><dd>{{ summary.permissionSha256 }}</dd><dt>原始权限规则</dt><dd><ol class="technical-rules"><li v-for="(rule, index) in summary.permissions" :key="index">{{ rule.action }} · {{ rule.permission }} · {{ rule.pattern }}</li></ol></dd></dl>
        </details>
      </template>
    </div>
  </section>
</template>

<style scoped>
.session-role{border-bottom:1px solid var(--color-border-default);background:var(--color-bg-surface);min-width:0}
.session-role-toggle{display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;padding:10px 16px;background:transparent;border:0;color:var(--color-text-primary);font:inherit;font-size:12px;cursor:pointer;text-align:left}
.session-role-toggle span:last-child,.role-note{color:var(--color-text-secondary)}
.session-role-toggle:focus-visible,.retry-button:focus-visible,.technical-details summary:focus-visible{outline:2px solid var(--color-accent-cyan);outline-offset:2px}
.session-role-content{padding:4px 16px 16px;font-size:12px;line-height:1.6}
.session-role-content p{margin:8px 0}.session-role-content h3{font-size:12px;margin:16px 0 8px}
.role-error{color:var(--color-task-danger)}.retry-button{background:none;border:0;color:var(--color-accent-cyan);font:inherit;text-decoration:underline;cursor:pointer}
.role-digests{display:flex;flex-wrap:wrap;gap:8px 20px}.role-digests strong{font-family:var(--font-code);font-weight:500}
.permission-rules{list-style:none;margin:0;padding:0;max-height:220px;overflow:auto}
.permission-rules li{display:flex;align-items:baseline;flex-wrap:wrap;gap:6px 10px;border-top:1px solid var(--color-border-default);padding:6px 0}
.permission-action{font-weight:600}.permission-action.allow{color:var(--color-success)}.permission-action.deny{color:var(--color-task-danger)}
.permission-rules code{font-family:var(--font-code);overflow-wrap:anywhere;min-width:0}
.technical-details{margin-top:12px}.technical-details summary{cursor:pointer;color:var(--color-text-secondary)}
.technical-details dl{display:grid;grid-template-columns:max-content minmax(0,1fr);gap:4px 12px;margin:8px 0 0}.technical-details dt{color:var(--color-text-secondary)}.technical-details dd{margin:0;font-family:var(--font-code);overflow-wrap:anywhere}
.technical-rules{margin:0;padding-left:18px}
@media(max-width:600px){.technical-details dl{grid-template-columns:1fr}.technical-details dd{margin-bottom:6px}}
</style>
