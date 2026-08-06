import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', redirect: '/tasks' },
    { path: '/projects', component: () => import('@/views/ProjectsView.vue') },
    { path: '/designer', component: () => import('@/views/DesignerView.vue') },
    { path: '/tasks', component: () => import('@/views/TasksView.vue') },
    { path: '/inbox', component: () => import('@/views/InboxView.vue') },
    { path: '/insights', component: () => import('@/views/InsightsDashboardView.vue') },
    { path: '/automations', component: () => import('@/views/AutomationsView.vue') },
    { path: '/tasks/:id', component: () => import('@/views/TaskDetailView.vue') },
    { path: '/tasks/:id/recovery', component: () => import('@/views/RecoveryStudioView.vue') },
    { path: '/tasks/:id/design', component: () => import('@/views/TaskDesignHistoryView.vue') },
    { path: '/runtime', component: () => import('@/views/RuntimeView.vue') },
    { path: '/settings', component: () => import('@/views/SettingsView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/tasks' },
  ],
})
