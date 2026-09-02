<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import { api } from '@/api/client'
import type { McpServerInfo, McpToolCatalog, Project } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const projects = ref<Project[]>([])
const projectId = ref('')
const servers = ref<McpServerInfo[]>([])
const catalogs = ref<Record<string, McpToolCatalog>>({})
const pending = ref<Record<string, boolean>>({})
const search = ref('')
const error = ref('')
const loading = ref(false)
const checkedAt = ref('')
let generation = 0
const statusLabels: Record<string, string> = { connected: '已连接', disabled: '已停用', failed: '连接失败', needs_auth: '等待授权', needs_client_registration: '等待注册', unknown: '状态未知' }
const visible = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return servers.value.filter(server => !query || server.name.toLocaleLowerCase().includes(query)
    || catalogs.value[server.id]?.tools.some(tool => `${tool.name} ${tool.description}`.toLocaleLowerCase().includes(query)))
})
async function load() {
  const request = ++generation
  loading.value = true; error.value = ''; catalogs.value = {}; pending.value = {}
  try {
    const value = await api.getMcpServers(projectId.value)
    if (request !== generation) return
    servers.value = value.servers; checkedAt.value = value.checkedAt
    if (!value.complete) error.value = 'MCP 服务数量超过单次展示上限，当前列表未完整加载'
  } catch (cause) { if (request === generation) { servers.value = []; error.value = userFacingError(cause, '无法读取 MCP 服务') } }
  finally { if (request === generation) loading.value = false }
}
async function loadTools(server: McpServerInfo) {
  if (pending.value[server.id]) return
  const request = generation
  pending.value[server.id] = true
  try {
    const value = await api.getMcpTools(projectId.value, server.id)
    if (request === generation) catalogs.value[server.id] = value
  } catch (cause) {
    if (request === generation) catalogs.value[server.id] = { tools: [], complete: false, detail: userFacingError(cause, '工具读取失败，请重试') }
  } finally { if (request === generation) pending.value[server.id] = false }
}
onMounted(() => {
  void load()
  void api.getProjects().then(value => { projects.value = value }).catch(() => { /* Global runtime remains available. */ })
})
</script>

<template>
  <PageHeader eyebrow="系统" title="工具"><template #actions><el-button :loading="loading" @click="load"><Icon icon="lucide:refresh-cw" />刷新</el-button></template></PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section class="card tools-toolbar">
      <el-select v-model="projectId" :empty-values="[null, undefined]" aria-label="工具所属项目" @change="load"><el-option label="全局运行环境" value="" /><el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" /></el-select>
      <el-input v-model="search" aria-label="搜索 MCP 和已读取工具" placeholder="搜索 MCP、已读取的工具和描述" clearable />
      <span>{{ servers.length }} 个 MCP 服务</span>
    </section>
    <p class="tools-hint">展开服务可查看工具名称和描述。此页面只读取工具清单。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取 MCP 服务…</p>
    <section v-else-if="!visible.length" class="card empty-state"><strong>{{ search ? '没有匹配的服务或已读取工具' : '当前项目没有配置 MCP 服务' }}</strong></section>
    <section v-else class="tool-servers" aria-label="MCP 服务列表">
      <details v-for="server in visible" :key="server.id" class="card tool-server" @toggle="(event) => { if ((event.target as HTMLDetailsElement).open && !catalogs[server.id]) loadTools(server) }">
        <summary><Icon icon="lucide:plug" /><strong>{{ server.name }}</strong><span :class="{ connected: server.status === 'connected' }">{{ statusLabels[server.status] ?? '状态未知' }}</span><small>{{ server.type === 'local' ? '本地' : server.type === 'remote' ? '远程' : '' }}</small></summary>
        <div class="tool-body">
          <p v-if="pending[server.id]" role="status">正在读取工具…</p>
          <template v-if="catalogs[server.id]">
            <p v-if="catalogs[server.id]?.detail" role="status">{{ catalogs[server.id]?.detail }} <el-button link @click="loadTools(server)">重试</el-button></p>
            <p v-if="catalogs[server.id]?.complete && !catalogs[server.id]?.tools.length">此服务未提供工具。</p>
            <p v-else-if="catalogs[server.id]?.tools.length">{{ catalogs[server.id]?.tools.length }} 个工具{{ catalogs[server.id]?.complete ? '' : '（部分结果）' }}</p>
            <article v-for="tool in catalogs[server.id]?.tools" :key="tool.name" class="tool-entry"><h3>{{ tool.name }}</h3><p>{{ tool.description || '服务端未提供描述' }}</p></article>
          </template>
        </div>
      </details>
    </section>
    <p v-if="checkedAt" class="tools-hint">最近读取：{{ new Date(checkedAt).toLocaleString('zh-CN') }}</p>
  </main>
</template>

<style scoped>
.tools-toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:14px;padding:16px}.tools-toolbar .el-select{width:240px}.tools-toolbar .el-input{flex:1;min-width:220px}.tools-toolbar span,.tools-hint{font-size:12px;color:var(--color-text-secondary)}.tool-servers{display:grid;gap:12px}.tool-server summary{display:flex;align-items:center;gap:12px;padding:18px;cursor:pointer}.tool-server summary strong{flex:1}.tool-server summary span,.tool-server small{font-size:12px;color:var(--color-text-secondary)}.tool-server .connected{color:var(--color-success)}.tool-body{padding:0 20px 16px;font-size:12px}.tool-entry{border-top:1px solid var(--color-border-default);padding:10px 0}.tool-entry h3{font:600 13px var(--font-code);overflow-wrap:anywhere}.tool-entry p{white-space:pre-wrap;overflow-wrap:anywhere;color:var(--color-text-secondary);line-height:1.7}
</style>
