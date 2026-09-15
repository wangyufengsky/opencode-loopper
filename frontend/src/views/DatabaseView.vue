<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import DatabaseConnectionDrawer from '@/components/DatabaseConnectionDrawer.vue'
import { api } from '@/api/client'
import type { DatabaseConnection, DatabaseDriver, DatabaseProbe, DatabaseTypeProfile, Project } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
const rows = ref<DatabaseConnection[]>([]), types = ref<DatabaseTypeProfile[]>([]), drivers = ref<DatabaseDriver[]>([]), projects = ref<Project[]>([])
const cursor = ref<string | null>(null), loading = ref(false), saving = ref(false), error = ref(''), editing = ref(false), selected = ref<DatabaseConnection | null>(null)
const query = ref(''), type = ref(''), state = ref('AVAILABLE'), probe = ref<Record<string, DatabaseProbe>>({}), testing = ref<string | null>(null)
const typeLabels = { MYSQL: 'MySQL', OPENGAUSS: 'openGauss', GAUSSDB: 'GaussDB', GOLDENDB: 'GoldenDB（历史配置）', DAMENG: '达梦', ORACLE: 'Oracle', DB2: 'DB2' }
let generation = 0
async function load(more = false) {
  const current = ++generation; loading.value = true; error.value = ''
  if (!more) { rows.value = []; cursor.value = null }
  try { const result = await api.getDatabaseConnections(more ? cursor.value ?? undefined : undefined, { query: query.value, type: type.value, state: state.value }); if (current === generation) { rows.value = more ? [...rows.value, ...result.items] : result.items; cursor.value = result.nextCursor ?? null } }
  catch (cause) { if (current === generation) error.value = userFacingError(cause, '连接列表读取失败，请重试') }
  finally { if (current === generation) loading.value = false }
}
watch([type, state], () => { void load() })
function edit(row: DatabaseConnection | null = null) { selected.value = row; editing.value = true }
async function update(row: DatabaseConnection, archive: boolean) {
  if (archive) { try { await ElMessageBox.confirm(`归档“${row.name}”后，新任务不能发现此连接。已冻结任务仍使用原授权。`, '归档连接', { confirmButtonText: '归档', cancelButtonText: '取消' }) } catch { return } }
  saving.value = true; error.value = ''
  try { await api.saveDatabaseConnection(row.id, { ...row, password: null, enabled: archive ? false : !row.enabled, archived: archive || row.archived }); await load() }
  catch (cause) { error.value = userFacingError(cause, '配置未更新，请刷新后重试') }
  finally { saving.value = false }
}
async function test(row: DatabaseConnection) {
  testing.value = row.id; error.value = ''; delete probe.value[row.id]
  try { probe.value[row.id] = await api.testDatabaseConnection(row.id) }
  catch (cause) { error.value = userFacingError(cause, '连接检查失败，请检查驱动、凭据及网络') }
  finally { testing.value = null }
}
function driverState(row: DatabaseConnection) { return row.config.driverProfile ? '内置驱动' : drivers.value.some(d => d.filename === row.config.driverFile) ? '驱动已安装' : '历史驱动缺失' }
onMounted(() => {
  void load()
  void Promise.all([api.getDatabaseTypes(), api.getDatabaseDrivers(), api.getProjects()]).then(([t, d, p]) => { types.value = t; drivers.value = d; projects.value = p }).catch(cause => { error.value = userFacingError(cause, '类型或项目清单读取失败，请刷新页面') })
})
</script>
<template>
  <PageHeader eyebrow="系统" title="数据库"><template #actions><el-button :loading="loading" @click="load()">刷新</el-button><el-button type="primary" :disabled="!types.length" @click="edit()"><Icon icon="lucide:plus" width="15" />新增连接</el-button></template></PageHeader>
  <main id="main-content" class="content database-page" tabindex="-1">
    <div class="database-intro"><Icon icon="lucide:shield-check" width="18" /><p>为项目提供结构查询与受控只读访问<span>选择数据库类型即可使用内置驱动，运行时无需联网下载。</span></p><span class="mode-badge">只读访问</span></div>
    <form class="filters card" @submit.prevent="load()"><el-input v-model="query" placeholder="搜索连接名称或主机" aria-label="搜索数据库" clearable @clear="load()"><template #prefix><Icon icon="lucide:search" /></template></el-input><el-select v-model="type" aria-label="筛选数据库类型"><el-option value="" label="全部类型" /><el-option v-for="(label, value) in typeLabels" :key="value" :value="value" :label="label" /></el-select><el-select v-model="state" aria-label="筛选连接状态"><el-option value="AVAILABLE" label="未归档" /><el-option value="ENABLED" label="已启用" /><el-option value="DISABLED" label="已停用" /><el-option value="ARCHIVED" label="已归档" /><el-option value="ALL" label="全部状态" /></el-select><el-button native-type="submit" :loading="loading">搜索</el-button></form>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <section v-if="!rows.length && !loading" class="card empty"><div class="empty-icon"><Icon icon="lucide:database" width="32" /></div><h2>{{ query || type || state !== 'AVAILABLE' ? '没有匹配的连接' : '连接你的第一套数据库' }}</h2><p>将数据库绑定到项目，让任务按需读取表结构和业务数据。</p><div class="supported"><span v-for="item in types" :key="item.id">{{ item.label }}<small>驱动已内置</small></span></div><el-button type="primary" :disabled="!types.length" @click="edit()">新增数据库连接</el-button></section>
    <div v-else class="card table-wrap" :aria-busy="loading"><table class="database-table"><thead><tr><th>连接</th><th>访问地址</th><th>授权项目</th><th>状态</th><th>操作</th></tr></thead><tbody><template v-for="row in rows" :key="row.id"><tr><td><strong>{{ row.name }}</strong><small>{{ typeLabels[row.config.type] }} · {{ driverState(row) }}</small></td><td><code>{{ row.config.jdbcUrl || `${row.config.host}:${row.config.port}` }}</code><small>{{ row.config.database }}</small></td><td><span>{{ row.projectIds.map(id => projects.find(p => p.id === id)?.name ?? '已登记项目').join('、') || '未绑定项目' }}</span><small>允许范围：{{ row.config.schemas.join('、') }}</small></td><td><span :class="['connection-state', { enabled: row.enabled && !row.archived }]">{{ row.archived ? '已归档' : row.enabled ? '已启用' : '已停用' }}</span><small>待现场版本联调</small></td><td><div class="row-actions"><el-button link :disabled="saving || row.archived || !types.some(t => t.type === row.config.type)" @click="edit(row)">编辑</el-button><el-button link :loading="testing === row.id" :disabled="testing !== null" @click="test(row)">测试连接</el-button><el-button v-if="!row.archived && (row.enabled || types.some(t => t.type === row.config.type))" link :disabled="saving" @click="update(row, false)">{{ row.enabled ? '停用' : '启用' }}</el-button><el-button v-if="!row.archived" link :disabled="saving" @click="update(row, true)">归档</el-button></div></td></tr><tr v-if="probe[row.id]" class="probe-row"><td colspan="5" role="status">连接成功 · 只读标记{{ probe[row.id]?.sessionReadOnly ? '已确认' : '未确认' }} · {{ probe[row.id]?.serverProduct }} {{ probe[row.id]?.serverVersion }}<p>{{ probe[row.id]?.detail }}</p></td></tr></template></tbody></table></div>
    <el-button v-if="cursor" :loading="loading" class="load-more" @click="load(true)">加载更多</el-button>
    <p class="footnote">全局连接配置，按项目授权。修改只影响新会话，已运行任务使用冻结配置。</p>
    <DatabaseConnectionDrawer v-model="editing" :row="selected" :types="types" :projects="projects" @saved="load()" />
  </main>
</template>
<style scoped>
.database-intro{display:flex;align-items:center;gap:12px;margin-bottom:24px;color:var(--color-text-secondary)}.database-intro>svg{color:var(--color-accent-cyan)}.database-intro p{font-size:14px;margin:0;flex:1}.database-intro p span{display:block;font-size:12px;color:var(--color-text-muted);margin-top:6px}.mode-badge{font-size:11px;border:1px solid var(--color-border-default);padding:5px 10px;border-radius:20px}.filters{display:flex;gap:12px;padding:14px;margin-bottom:20px}.filters>.el-input{flex:1}.filters>.el-select{width:150px}.empty{display:grid;justify-items:center;text-align:center;padding:58px 24px}.empty-icon{width:72px;height:72px;display:grid;place-items:center;border:1px solid var(--color-border-default);border-radius:20px;color:var(--color-accent-cyan);background:var(--color-bg-elevated);margin-bottom:18px}.empty h2{font-size:20px;margin:0 0 12px}.empty p{font-size:13px;color:var(--color-text-secondary);margin:0}.supported{display:flex;gap:12px;margin:28px 0}.supported>span{border:1px solid var(--color-border-default);border-radius:8px;padding:12px 24px;font-size:13px;min-width:105px}.supported small{display:block;font-size:10px;margin-top:6px;color:var(--color-text-muted)}.table-wrap{overflow:auto}.database-table{width:100%;border-collapse:collapse;text-align:left;font-size:13px;min-width:800px}.database-table th{font-size:11px;font-weight:500;color:var(--color-text-muted);padding:14px 18px;border-bottom:1px solid var(--color-border-default)}.database-table td{padding:20px 18px;vertical-align:top;border-bottom:1px solid var(--color-border-default);max-width:270px;overflow-wrap:anywhere}.database-table strong{font-weight:600}.database-table small{display:block;font-size:11px;color:var(--color-text-muted);margin-top:7px;line-height:1.5}.database-table code{font-size:12px}.row-actions{display:flex;flex-wrap:wrap;gap:10px}.row-actions .el-button{margin:0;font-size:12px}.connection-state{font-size:12px}.connection-state:before{content:'';display:inline-block;width:6px;height:6px;border-radius:50%;margin-right:6px;background:var(--color-text-muted)}.connection-state.enabled:before{background:var(--color-success)}.footnote{font-size:12px;color:var(--color-text-muted);margin-top:20px}.error{color:var(--color-task-danger)}.probe-row{font-size:12px;color:var(--color-text-secondary)}.load-more{margin-top:16px}@media(max-width:720px){.filters{flex-wrap:wrap}.filters>.el-input{flex-basis:100%}.filters>.el-select{flex:1;min-width:110px}.mode-badge{display:none}.supported{gap:8px;flex-wrap:wrap;justify-content:center}.supported>span{padding:12px}.empty{padding:36px 16px}}
</style>
