<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { api } from '@/api/client'
import type { DatabaseConnection, DatabaseConnectionInput, DatabaseDriver, DatabaseProbe, Project } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const rows = ref<DatabaseConnection[]>([]), drivers = ref<DatabaseDriver[]>([]), projects = ref<Project[]>([])
const cursor = ref<string | null>(null), loading = ref(false), saving = ref(false), error = ref(''), editing = ref(false)
const selected = ref<string | null>(null), password = ref(''), schemas = ref(''), parameters = ref('{}')
const probe = ref<Record<string, DatabaseProbe>>({}), testing = ref<string | null>(null)
const typeLabels = { MYSQL: 'MySQL', GAUSSDB: 'GaussDB／openGauss', GOLDENDB: 'GoldenDB', DAMENG: '达梦' }
const empty = (): DatabaseConnectionInput => ({ name: '', password: null, enabled: true, archived: false, projectIds: [], version: 0,
  config: { type: 'MYSQL', host: '', port: 3306, database: '', username: '', driverFile: '', driverClass: '', schemas: [], parameters: {}, timeoutSeconds: 10, maxRows: 200 } })
const form = ref<DatabaseConnectionInput>(empty())
async function load(more = false) {
  loading.value = true; error.value = ''
  try { const result = await api.getDatabaseConnections(more ? cursor.value ?? undefined : undefined); rows.value = more ? [...rows.value, ...result.items] : result.items; cursor.value = result.nextCursor ?? null }
  catch (cause) { error.value = userFacingError(cause, '连接列表读取失败，请重试') }
  finally { loading.value = false }
}
function edit(row?: DatabaseConnection) {
  selected.value = row?.id ?? null; form.value = row ? { name: row.name, config: JSON.parse(JSON.stringify(row.config)), enabled: row.enabled, archived: row.archived, projectIds: [...row.projectIds], version: row.version, password: null } : empty()
  password.value = ''; schemas.value = form.value.config.schemas.join(', '); parameters.value = JSON.stringify(form.value.config.parameters, null, 2); editing.value = true; error.value = ''
}
async function save() {
  saving.value = true; error.value = ''
  try {
    const parsed: unknown = JSON.parse(parameters.value)
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object' || Object.values(parsed).some(v => typeof v !== 'string')) throw new Error('连接参数必须为字符串键值对象')
    const config = { ...form.value.config, schemas: schemas.value.split(/[,，\n]/).map(s => s.trim()).filter(Boolean), parameters: parsed as Record<string, string> }
    await api.saveDatabaseConnection(selected.value, { ...form.value, config, password: password.value || null })
    password.value = ''; editing.value = false; await load()
  } catch (cause) { error.value = userFacingError(cause, '保存失败，请检查配置；若发生版本冲突请刷新后重新编辑') }
  finally { saving.value = false }
}
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
onMounted(() => {
  void load()
  void Promise.all([api.getDatabaseDrivers(), api.getProjects()]).then(([d, p]) => { drivers.value = d; projects.value = p }).catch(cause => { error.value = userFacingError(cause, '驱动或项目清单读取失败，请刷新') })
})
</script>

<template>
  <PageHeader eyebrow="系统" title="数据库"><template #actions><el-button :loading="loading" @click="load()">刷新</el-button><el-button type="primary" @click="edit()">新增连接</el-button></template></PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <p class="hint">仅提供结构查询和受控只读查询。连接需绑定项目；已运行任务继续使用冻结配置。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <section v-if="!rows.length && !loading" class="card">尚未配置数据库连接。</section>
    <div class="connections">
      <section v-for="row in rows" :key="row.id" class="card connection">
        <h2>{{ row.name }} <small>{{ row.archived ? '已归档' : row.enabled ? '已启用' : '已停用' }}</small></h2>
        <p>{{ typeLabels[row.config.type] }} · {{ row.config.host }}:{{ row.config.port }} / {{ row.config.database }}</p>
        <p>绑定项目：{{ row.projectIds.map(id => projects.find(p => p.id === id)?.name ?? '已登记项目').join('、') || '未绑定' }}</p>
        <p>{{ drivers.some(d => d.filename === row.config.driverFile) ? '驱动已安装' : '驱动缺失' }} · {{ row.config.driverFile }}</p>
        <p>兼容性：待现场版本联调</p>
        <div v-if="probe[row.id]" role="status"><p>连接成功 · 只读标记{{ probe[row.id]?.sessionReadOnly ? '已确认' : '未确认' }}</p><p>{{ probe[row.id]?.serverProduct }} {{ probe[row.id]?.serverVersion }} · 驱动 {{ probe[row.id]?.driverVersion }}</p><p>{{ probe[row.id]?.detail }}</p></div>
        <div class="actions"><el-button :disabled="saving" @click="edit(row)">编辑</el-button><el-button :loading="testing === row.id" :disabled="testing !== null" @click="test(row)">测试连接</el-button><el-button v-if="!row.archived" :disabled="saving" @click="update(row, false)">{{ row.enabled ? '停用' : '启用' }}</el-button><el-button v-if="!row.archived" :disabled="saving" @click="update(row, true)">归档</el-button></div>
      </section>
    </div>
    <el-button v-if="cursor" :loading="loading" @click="load(true)">加载更多</el-button>
    <details class="card drivers"><summary>离线驱动（{{ drivers.length }}）</summary><p>由管理员将匹配的厂商 JDBC 驱动放入受管数据目录 jdbc-drivers；修改后刷新页面。</p><p v-for="driver in drivers" :key="driver.filename">{{ driver.filename }} · {{ driver.sizeBytes }} 字节<br /><code>{{ driver.sha256 }}</code></p></details>
    <el-dialog v-model="editing" :title="selected ? '编辑连接' : '新增连接'" width="min(760px, 95vw)" :close-on-click-modal="!saving" @closed="password = ''">
      <el-form label-position="top" @submit.prevent="save">
        <div class="fields">
          <el-form-item label="连接名称"><el-input v-model="form.name" maxlength="100" /></el-form-item>
          <el-form-item label="数据库类型"><el-select v-model="form.config.type"><el-option v-for="(label, value) in typeLabels" :key="value" :value="value" :label="label" /></el-select></el-form-item>
          <el-form-item label="主机"><el-input v-model="form.config.host" /></el-form-item><el-form-item label="端口"><el-input-number v-model="form.config.port" :min="1" :max="65535" /></el-form-item>
          <el-form-item label="数据库／默认 schema"><el-input v-model="form.config.database" /></el-form-item><el-form-item label="只读账号"><el-input v-model="form.config.username" autocomplete="off" /></el-form-item>
          <el-form-item :label="selected ? '新密码（留空保留原密码）' : '密码'"><el-input v-model="password" type="password" autocomplete="new-password" /></el-form-item>
          <el-form-item label="允许访问的 schema／数据库（逗号分隔）"><el-input v-model="schemas" /></el-form-item>
          <el-form-item label="已安装驱动文件"><el-select v-model="form.config.driverFile" filterable allow-create><el-option v-for="driver in drivers" :key="driver.filename" :value="driver.filename" :label="driver.filename" /></el-select></el-form-item>
          <el-form-item label="厂商驱动类"><el-input v-model="form.config.driverClass" placeholder="按匹配版本的厂商说明填写" /></el-form-item>
          <el-form-item label="查询超时（秒）"><el-input-number v-model="form.config.timeoutSeconds" :min="1" :max="30" /></el-form-item>
          <el-form-item label="最多返回行数"><el-input-number v-model="form.config.maxRows" :min="1" :max="1000" /></el-form-item>
        </div>
        <el-form-item label="绑定项目"><el-select v-model="form.projectIds" multiple><el-option v-for="project in projects" :key="project.id" :value="project.id" :label="project.name" /></el-select></el-form-item>
        <details><summary>受控连接参数</summary><el-input v-model="parameters" type="textarea" :rows="3" aria-label="连接参数 JSON" /><p>只接受适配器白名单中的 TLS、时区和编码参数。</p></details>
        <el-checkbox v-model="form.enabled">启用</el-checkbox><el-checkbox v-if="selected" v-model="form.archived">已归档</el-checkbox>
        <p v-if="error" role="alert">{{ error }}</p>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="editing = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </main>
</template>
<style scoped>
.connections{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.connection,.drivers{padding:20px;overflow-wrap:anywhere}.connection h2{font-size:16px}.connection p,.hint,.drivers{font-size:13px;color:var(--color-text-secondary)}small{font-weight:400;font-size:12px}.actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}.actions .el-button{margin:0}.fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 20px;align-items:end}.fields .el-select,.el-input-number{width:100%}.drivers{margin-top:20px}.drivers code{font-size:11px}@media(max-width:720px){.connections,.fields{grid-template-columns:1fr}}
</style>
