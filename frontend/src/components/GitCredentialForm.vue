<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElLoading, ElMessageBox, ElOption, ElRadioButton, ElRadioGroup, ElSelect } from 'element-plus'
import { api } from '@/api/client'
import type { GitCredentialInput, GitCredentialView } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const vLoading = ElLoading.directive
const props = defineProps<{ projectId?: string; demo?: boolean }>()
const saved = ref<GitCredentialView>()
const mode = ref<'CUSTOM' | 'INHERIT'>(props.projectId ? 'INHERIT' : 'CUSTOM')
const serverUrl = ref(''), username = ref(''), secret = ref(''), repositoryUrl = ref('')
const kind = ref<'TOKEN' | 'PASSWORD'>('TOKEN')
const loading = ref(false), saving = ref(false), testing = ref(false)
const error = ref(''), message = ref('')
const probe = ref<{ success: boolean; message: string }>()
let generation = 0
const busy = computed(() => loading.value || saving.value || testing.value || !!props.demo)
const ownSecret = computed(() => saved.value?.mode === 'CUSTOM' && saved.value.configured)
const inherited = computed(() => !!props.projectId && mode.value === 'INHERIT')
watch([mode, serverUrl, username, secret, kind, repositoryUrl], () => { generation++; probe.value = undefined; message.value = '' })
onBeforeUnmount(() => { generation++; secret.value = '' })
function input(): GitCredentialInput {
  return { mode: mode.value, serverUrl: serverUrl.value.trim(), username: username.value.trim(), kind: kind.value,
    secret: secret.value || undefined, version: saved.value?.version ?? 0, repositoryUrl: repositoryUrl.value.trim() || undefined }
}
function apply(view: GitCredentialView) {
  saved.value = view; mode.value = props.projectId && view.mode === 'INHERIT' ? 'INHERIT' : 'CUSTOM'
  serverUrl.value = view.serverUrl; username.value = view.username; kind.value = view.kind; secret.value = ''
}
async function load() {
  if (props.demo) return
  loading.value = true; error.value = ''
  try { apply(await api.gitCredentials(props.projectId)) }
  catch (failure) { error.value = userFacingError(failure, 'Git 账号读取失败，请重新加载') }
  finally { loading.value = false }
}
async function save(disable = false) {
  if (busy.value) return
  if (disable) { try { await ElMessageBox.confirm('停用后，继承全局账号的项目将使用原有系统 Git 凭据。项目独立账号不受影响。', '停用全局 Git 账号', { type: 'warning' }) } catch { return } }
  saving.value = true; error.value = ''
  try {
    apply(await api.saveGitCredentials(props.projectId, { ...input(), ...(disable ? { mode: 'DISABLED' as const, secret: undefined } : {}) }))
    await Promise.resolve(); message.value = disable ? '全局 Git 账号已停用' : 'Git 账号已保存，将用于后续 Git 远程操作'
  } catch (failure) { error.value = userFacingError(failure, 'Git 账号保存失败，请检查输入或重新加载') }
  finally { saving.value = false }
}
async function test() {
  if (busy.value) return
  testing.value = true; error.value = ''; probe.value = undefined
  const current = generation
  try { const result = await api.testGitCredentials(props.projectId, input()); if (current === generation) probe.value = result }
  catch (failure) { if (current === generation) error.value = userFacingError(failure, 'Git 连接验证失败，请检查账号和仓库地址') }
  finally { testing.value = false }
}
onMounted(load)
</script>

<template>
  <section v-loading="loading" class="git-credentials" aria-label="Git 账号管理">
    <p class="help">{{ projectId ? '项目默认继承全局 Git 账号，也可以单独配置。' : '为同一 Git 服务器设置默认账号，项目默认继承，可单独覆盖。' }}</p>
    <el-alert v-if="demo" title="演示模式不保存或验证 Git 凭据" type="info" :closable="false" />
    <el-radio-group v-if="projectId" v-model="mode" :disabled="busy" aria-label="Git 账号来源">
      <el-radio-button value="INHERIT">继承全局账号</el-radio-button><el-radio-button value="CUSTOM">使用独立账号</el-radio-button>
    </el-radio-group>
    <p v-if="inherited" class="help">{{ saved?.source === 'GLOBAL' && saved.configured ? `全局账号：${saved.username} · ${saved.serverUrl}` : '保存继承设置后使用全局账号；可在设置页查看和维护。' }}</p>
    <el-form v-else label-position="top" :disabled="busy" @submit.prevent="save()">
      <div class="credential-grid">
        <el-form-item label="Git 服务器地址"><el-input v-model="serverUrl" aria-label="Git 服务器地址" placeholder="https://gitlab.example.com" autocomplete="off" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="username" aria-label="Git 用户名" autocomplete="off" /></el-form-item>
        <el-form-item label="认证方式"><el-select v-model="kind" aria-label="Git 认证方式"><el-option value="TOKEN" label="访问令牌（推荐）" /><el-option value="PASSWORD" label="账号密码" /></el-select></el-form-item>
        <el-form-item :label="kind === 'TOKEN' ? '访问令牌' : '密码'"><el-input v-model="secret" aria-label="Git 密码或令牌" type="password" show-password autocomplete="new-password" :placeholder="ownSecret ? '已加密保存；留空保持不变' : '请输入密码或令牌'" /></el-form-item>
      </div>
      <p class="help">仅用于 HTTP(S) Git 操作。启用双重认证的 GitLab 通常需要访问令牌；账号密码是否可用取决于服务器配置。</p>
      <el-alert v-if="serverUrl.startsWith('http://')" title="当前地址使用 HTTP，网络传输不加密；服务器支持时请使用 HTTPS。" type="warning" :closable="false" />
    </el-form>
    <el-form label-position="top" :disabled="busy"><el-form-item :label="projectId ? '验证仓库地址（留空使用项目 origin）' : '验证仓库地址'"><el-input v-model="repositoryUrl" aria-label="Git 验证仓库地址" placeholder="https://gitlab.example.com/group/repository.git" autocomplete="off" /></el-form-item></el-form>
    <p class="help">验证仅检查仓库读取权限，不会推送代码；验证成功后仍需保存。密码和令牌不会回显。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <el-alert v-if="probe" :title="probe.message" :type="probe.success ? 'success' : 'error'" :closable="false" />
    <el-alert v-if="message" :title="message" type="success" :closable="false" />
    <div class="credential-actions">
      <el-button type="primary" :disabled="busy" :loading="saving" @click="save()">保存 Git 账号</el-button>
      <el-button :disabled="busy" :loading="testing" @click="test">验证连接</el-button>
      <el-button :disabled="busy" @click="load">重新加载</el-button>
      <el-button v-if="!projectId && saved?.configured" type="danger" plain :disabled="busy" @click="save(true)">停用全局账号</el-button>
    </div>
  </section>
</template>

<style scoped>
.git-credentials { display: grid; gap: 18px; min-width: 0; }
.credential-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.credential-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.credential-actions .el-button { margin-left: 0; }
.help { color: var(--color-text-secondary); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; margin: 0; }
@media (max-width: 700px) { .credential-grid { grid-template-columns: 1fr; } }
</style>
