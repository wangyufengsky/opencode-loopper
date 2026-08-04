<script setup lang="ts">
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { useTaskStore } from '@/stores/taskStore'

const store = useTaskStore()
const settings = ref({ cliPath: 'opencode', allowedRoot: '', provider: 'opencode', model: 'deepseek-v4-flash-free', maxTaskAttempts: 12, timeoutMinutes: 30, autoApprove: false })
const fieldError = ref('')

function isAbsoluteProjectPath(value: string) {
  return value.startsWith('/') || /^[A-Za-z]:[\\/]/.test(value) || /^\\\\[^\\]+\\[^\\]+/.test(value)
}

function save() {
  fieldError.value = ''
  if (!settings.value.cliPath.trim()) { fieldError.value = 'OpenCode CLI 路径不能为空。'; return }
  if (settings.value.allowedRoot.trim() && !isAbsoluteProjectPath(settings.value.allowedRoot.trim())) { fieldError.value = '允许项目根必须是 POSIX、Windows 盘符或 UNC 绝对路径。'; return }
  // Settings persistence is intentionally omitted until the backend Settings contract is available.
  ElMessage.info('设置已完成前端校验；当前未提供 /api/settings，因此不会持久化。')
}
</script>

<template>
  <PageHeader eyebrow="System / Settings" title="执行策略与模型" subtitle="密钥永不显示或持久化到浏览器；当前页面仅校验输入，等待 /api/settings 契约后才会保存。">
    <template #actions><el-button type="primary" @click="save"><Icon icon="lucide:save" />保存设置</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1"><section class="settings-layout"><article class="card card-pad"><div class="card-header"><div><p class="eyebrow">RUNTIME</p><h2 class="card-title">OpenCode 发现</h2></div><Icon icon="lucide:terminal-square" color="var(--color-accent-cyan)" /></div><el-form label-position="top"><el-form-item label="CLI 路径"><el-input v-model="settings.cliPath" class="mono" name="opencode-cli" autocomplete="off" /><p v-if="fieldError" class="inline-field-error"><Icon icon="lucide:circle-alert" /> {{ fieldError }}</p></el-form-item><el-form-item label="允许项目根（可选）"><el-input v-model="settings.allowedRoot" class="mono" name="allowed-project-root" autocomplete="off" placeholder="/workspace/my-project 或 C:\\workspace\\my-project" /></el-form-item></el-form></article><article class="card card-pad"><div class="card-header"><div><p class="eyebrow">MODEL</p><h2 class="card-title">默认模型</h2></div><Icon icon="lucide:brain-circuit" color="var(--color-accent-ai)" /></div><el-form label-position="top"><el-form-item label="Provider"><el-select v-model="settings.provider" style="width:100%"><el-option label="OpenCode" value="opencode" /></el-select></el-form-item><el-form-item label="Model"><el-input v-model="settings.model" class="mono" /></el-form-item></el-form><p class="card-description">Provider 密钥只从本机 OpenCode 环境读取，不会传递至此页面。</p></article><article class="card card-pad"><div class="card-header"><div><p class="eyebrow">LIMITS</p><h2 class="card-title">调度上限</h2></div><Icon icon="lucide:gauge" color="var(--color-session-warning)" /></div><el-form label-position="top"><el-form-item label="每 Task 最大 Attempt"><el-input-number v-model="settings.maxTaskAttempts" :min="1" :max="50" style="width:100%" /></el-form-item><el-form-item label="单 Attempt 超时（分钟）"><el-input-number v-model="settings.timeoutMinutes" :min="1" :max="120" style="width:100%" /></el-form-item></el-form></article><article class="card card-pad"><div class="card-header"><div><p class="eyebrow">PERMISSIONS</p><h2 class="card-title">自动批准策略</h2></div><Icon icon="lucide:shield-check" color="var(--color-success)" /></div><el-switch v-model="settings.autoApprove" active-text="按 Task 自动批准 ask 权限" inactive-text="所有 ask 权限均等待确认" /><p class="card-description" style="margin-top:16px">外部路径、git push、显式 deny 与破坏性命令始终不会自动批准。</p></article></section><section class="card card-pad settings-demo"><div><p class="eyebrow">DEVELOPMENT DATA</p><h2 class="card-title">演示数据模式</h2><p class="card-description">只用于前端交互验收；不会覆盖或掩盖真实 REST 错误。</p></div><el-button :type="store.usingDemo ? 'warning' : 'primary'" plain @click="store.activateDemo('已手动启用演示数据。')">{{ store.usingDemo ? '演示数据已启用' : '启用演示数据' }}</el-button></section></main>
</template>

<style scoped>
.settings-layout { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }.settings-demo { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; }
</style>
