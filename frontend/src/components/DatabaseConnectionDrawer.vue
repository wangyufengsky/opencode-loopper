<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDrawer } from 'element-plus'
import { api } from '@/api/client'
import type { DatabaseConnection, DatabaseConnectionInput, DatabaseTypeProfile, DatabaseProbe, Project } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ modelValue: boolean; row: DatabaseConnection | null; types: DatabaseTypeProfile[]; projects: Project[] }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()
const blank = (): DatabaseConnectionInput => ({ name: '', password: null, enabled: true, archived: false, projectIds: [], version: 0, config: { type: 'MYSQL', host: '', port: 3306, database: '', username: '', driverFile: '', driverClass: '', schemas: [], parameters: {}, timeoutSeconds: 10, maxRows: 200 } })
const form = ref(blank()), password = ref(''), schemas = ref(''), error = ref(''), saving = ref(false), testing = ref(false), probe = ref<DatabaseProbe | null>(null)
let revision = 0
const portEdited = ref(false)
const profile = computed(() => props.types.find(p => p.type === form.value.config.type))
const mysql = computed(() => form.value.config.type === 'MYSQL')
watch(() => props.modelValue, open => {
  revision++; portEdited.value = false; password.value = ''; error.value = ''; probe.value = null
  if (!open) return
  const row = props.row
  form.value = row ? { name: row.name, config: JSON.parse(JSON.stringify(row.config)), enabled: row.enabled, archived: row.archived, projectIds: [...row.projectIds], version: row.version, password: null } : blank()
  // Editing connection details upgrades a supported legacy connection to the managed profile.
  form.value.config.driverFile = ''; form.value.config.driverClass = ''; form.value.config.driverProfile = null
  schemas.value = form.value.config.schemas.join(', ')
})
watch([form, password, schemas], () => { revision++; probe.value = null }, { deep: true, flush: 'sync' })
function changeType() {
  const oldDefault = props.types.find(p => p.defaultPort === form.value.config.port)
  if (!portEdited.value && oldDefault && profile.value) form.value.config.port = profile.value.defaultPort
  form.value.config.parameters = {}; form.value.config.driverProfile = null
}
function body(): DatabaseConnectionInput {
  if (!profile.value || !form.value.name.trim() || !form.value.config.host.trim() || !form.value.config.database.trim() || !form.value.config.username.trim()) throw new Error('请填写名称、主机、数据库和只读账号')
  const allowed = schemas.value.split(/[,，\n]/).map(s => s.trim()).filter(Boolean)
  if (!allowed.length) throw new Error('请填写允许访问的数据库或 schema')
  if (!props.row && !password.value) throw new Error('请填写数据库密码')
  return { ...form.value, config: { ...form.value.config, schemas: allowed, parameters: Object.fromEntries(Object.entries(form.value.config.parameters).filter(([, v]) => v)) }, password: password.value || null }
}
async function save() {
  saving.value = true; error.value = ''
  try { await api.saveDatabaseConnection(props.row?.id ?? null, body()); password.value = ''; emit('update:modelValue', false); emit('saved') }
  catch (cause) { error.value = userFacingError(cause, '保存失败，请检查配置；版本冲突时请关闭并刷新后重新编辑') }
  finally { saving.value = false }
}
async function test() {
  testing.value = true; error.value = ''; probe.value = null; const current = revision
  try { const result = await api.testDatabaseDraft(props.row?.id ?? null, body()); if (current === revision && props.modelValue) probe.value = result }
  catch (cause) { if (current === revision) error.value = userFacingError(cause, '连接检查失败，请检查只读账号、地址和网络') }
  finally { testing.value = false }
}
</script>
<template>
  <el-drawer :model-value="modelValue" :title="row ? '编辑数据库连接' : '新增数据库连接'" size="min(640px, 100vw)" :close-on-click-modal="!saving && !testing" :close-on-press-escape="!saving && !testing" :show-close="!saving && !testing" @update:model-value="emit('update:modelValue', $event)">
    <el-form label-position="top" class="connection-form" :disabled="saving" @submit.prevent="save">
      <section><h3><span>01</span>基本信息</h3><div class="fields">
        <el-form-item label="连接名称"><el-input v-model="form.name" maxlength="100" placeholder="例如：业务只读库" /></el-form-item>
        <el-form-item label="数据库类型"><el-select v-model="form.config.type" @change="changeType"><el-option v-for="type in types" :key="type.id" :value="type.type" :label="type.label" /></el-select></el-form-item>
      </div><p class="driver-note">{{ profile ? `已内置 ${profile.label} 驱动 · ${profile.binaries[0]?.filename}` : '此历史类型暂不支持新增或修改连接配置' }}</p></section>
      <section><h3><span>02</span>连接信息</h3><div class="fields host-fields">
        <el-form-item label="主机"><el-input v-model="form.config.host" placeholder="数据库 IP 或内网域名" /></el-form-item><el-form-item label="端口"><el-input-number v-model="form.config.port" :min="1" :max="65535" :controls="false" @change="portEdited = true" /></el-form-item>
      </div><div class="fields"><el-form-item :label="form.config.type === 'DAMENG' ? '数据库标识' : '数据库名称'"><el-input v-model="form.config.database" /></el-form-item><el-form-item label="只读账号"><el-input v-model="form.config.username" autocomplete="off" /></el-form-item></div>
      <el-form-item :label="row ? '新密码' : '密码'"><el-input v-model="password" type="password" autocomplete="new-password" :placeholder="row ? '留空保留原密码' : '使用数据库只读账号的密码'" /></el-form-item></section>
      <section><h3><span>03</span>访问范围</h3><el-form-item :label="mysql ? '允许访问的数据库' : '允许访问的 schema'"><el-input v-model="schemas" placeholder="多个名称用逗号分隔" /></el-form-item>
      <el-form-item label="绑定项目"><el-select v-model="form.projectIds" multiple filterable placeholder="选择可以使用此连接的项目"><el-option v-for="project in projects" :key="project.id" :value="project.id" :label="project.name" /></el-select></el-form-item><p class="hint">未绑定项目时，任务无法发现此连接。</p></section>
      <details class="advanced"><summary>高级设置 <span>查询限额与连接安全</span></summary><div class="fields">
        <el-form-item label="查询超时（秒）"><el-input-number v-model="form.config.timeoutSeconds" :min="1" :max="30" controls-position="right" /></el-form-item><el-form-item label="最多返回行数"><el-input-number v-model="form.config.maxRows" :min="1" :max="1000" controls-position="right" /></el-form-item>
        <el-form-item v-if="mysql" label="TLS 连接"><el-select v-model="form.config.parameters.useSSL" clearable><el-option label="驱动默认" value="" /><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select></el-form-item>
        <el-form-item v-if="form.config.type === 'OPENGAUSS'" label="TLS 模式"><el-select v-model="form.config.parameters.sslmode" clearable><el-option v-for="mode in [{value:'require',label:'要求加密'},{value:'verify-full',label:'校验证书与主机名'},{value:'disable',label:'停用'}]" :key="mode.value" :label="mode.label" :value="mode.value" /></el-select></el-form-item>
        <el-form-item v-if="mysql" label="服务器时区"><el-input v-model="form.config.parameters.serverTimezone" placeholder="例如：Asia/Shanghai" /></el-form-item>
      </div></details>
      <div class="enable-row"><div><strong>启用连接</strong><p>供绑定项目的新会话使用</p></div><el-switch v-model="form.enabled" aria-label="启用连接" /></div>
      <div v-if="probe" class="probe" role="status"><strong>连接成功 · {{ probe.sessionReadOnly ? '只读标记已确认' : '只读控制未通过' }}</strong><p>{{ probe.serverProduct }} {{ probe.serverVersion }}</p><p>{{ probe.detail }}</p><small>完整兼容性：待现场版本联调</small></div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </el-form>
    <template #footer><div class="drawer-footer"><el-button :loading="testing" :disabled="saving || !profile" @click="test">测试连接</el-button><span /><el-button :disabled="saving || testing" @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :loading="saving" :disabled="testing || !profile" @click="save">保存连接</el-button></div></template>
  </el-drawer>
</template>
<style scoped>
.connection-form section{padding:0 0 20px;margin-bottom:22px;border-bottom:1px solid var(--color-border-default)}h3{display:flex;gap:10px;align-items:center;margin:0 0 18px;font-size:14px}h3 span{font-size:11px;color:var(--color-text-muted);font-variant-numeric:tabular-nums}.fields{display:grid;grid-template-columns:1fr 1fr;gap:0 18px;align-items:end}.host-fields{grid-template-columns:1fr 135px}.el-select,.el-input-number{width:100%}.hint,.driver-note,.enable-row p{font-size:12px;line-height:1.6;color:var(--color-text-secondary);margin:0}.driver-note{padding:10px 12px;background:var(--color-bg-elevated);border-radius:6px;overflow-wrap:anywhere}.advanced{margin-bottom:24px}.advanced summary{cursor:pointer;font-size:13px;padding:0 0 18px}.advanced summary span{float:right;color:var(--color-text-muted);font-size:12px}.enable-row{display:flex;justify-content:space-between;align-items:center;font-size:13px}.enable-row p{margin-top:6px}.drawer-footer{display:flex;gap:8px}.drawer-footer>span{flex:1}.drawer-footer .el-button{margin:0}.probe{margin-top:20px;border:1px solid var(--color-border-default);border-radius:8px;padding:14px;font-size:12px;line-height:1.6}.error{color:var(--color-task-danger);font-size:13px;line-height:1.6}@media(max-width:480px){.fields{grid-template-columns:1fr}.host-fields{grid-template-columns:1fr 105px}}
</style>
