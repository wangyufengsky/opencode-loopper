<script setup lang="ts">
import { Icon } from '@iconify/vue'
import { storeToRefs } from 'pinia'
import { useTaskStore } from '@/stores/taskStore'

const taskStore = useTaskStore()
const { runtime } = storeToRefs(taskStore)

const navigation = [
  { to: '/projects', icon: 'lucide:folder-kanban', label: '项目' },
  { to: '/designer', icon: 'lucide:sparkles', label: 'Designer / LoopSpec' },
  { to: '/tasks', icon: 'lucide:orbit', label: '任务' },
]
</script>

<template>
  <aside class="app-sidebar">
    <RouterLink class="brand" to="/tasks" aria-label="OpenCode Loopper 首页">
      <span class="brand-mark"><Icon icon="lucide:orbit" /></span>
      <span class="brand-copy">OpenCode Loopper<small>local control plane</small></span>
    </RouterLink>

    <p class="nav-label">Workspace</p>
    <nav aria-label="主导航">
      <RouterLink v-for="item in navigation" :key="item.to" class="nav-item" :to="item.to">
        <Icon :icon="item.icon" width="17" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <p class="nav-label">System</p>
    <nav aria-label="系统导航">
      <RouterLink class="nav-item" to="/runtime"><Icon icon="lucide:cpu" width="17" /><span>Runtime</span></RouterLink>
      <RouterLink class="nav-item" to="/settings"><Icon icon="lucide:settings-2" width="17" /><span>设置</span></RouterLink>
    </nav>

    <div class="sidebar-spacer" />
    <RouterLink class="runtime-mini" to="/runtime">
      <div class="runtime-mini-title"><span>OPENCODE RUNTIME</span><span :class="['status-badge', runtime?.status === 'ONLINE' ? 'status-success' : 'status-danger']">{{ runtime?.status ?? 'CHECKING' }}</span></div>
      <strong>{{ runtime?.model ?? '等待连接' }}</strong>
    </RouterLink>
  </aside>
</template>
