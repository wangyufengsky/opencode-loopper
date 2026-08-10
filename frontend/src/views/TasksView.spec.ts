import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h, type Slots, type VNode } from 'vue'
import { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useTaskStore } from '@/stores/taskStore'
import TasksView from '@/views/TasksView.vue'
import type { Task } from '@/types/domain'

const tasks: Task[] = [
  { id: 'new-a', projectId: 'project-a', projectName: '项目 A', title: 'A 的新任务', goal: 'A new', branch: 'DIRECT', worktreePath: '/a', status: 'SUCCEEDED', hasDesignHistory: true, attemptCount: 1, maxAttempts: 3, createdAt: '2026-08-05T08:00:00Z', updatedAt: '2026-08-05T09:00:00Z' },
  { id: 'old-a', projectId: 'project-a', projectName: '项目 A', title: 'A 的旧任务', goal: 'A old', branch: 'DIRECT', worktreePath: '/a', status: 'FAILED', hasDesignHistory: true, attemptCount: 2, maxAttempts: 3, createdAt: '2026-08-03T08:00:00Z', updatedAt: '2026-08-03T09:00:00Z' },
  { id: 'middle-b', projectId: 'project-b', projectName: '项目 B', title: 'B 的任务', goal: 'B middle', branch: 'DIRECT', worktreePath: '/b', status: 'SUCCEEDED', hasDesignHistory: true, attemptCount: 1, maxAttempts: 3, createdAt: '2026-08-04T08:00:00Z', updatedAt: '2026-08-04T09:00:00Z' },
]

let router: ReturnType<typeof createRouter>

beforeEach(async () => {
  vi.restoreAllMocks()
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useTaskStore()
  store.usingDemo = true
  store.tasks = structuredClone(tasks)
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/tasks', component: TasksView },
    { path: '/tasks/:id', component: { template: '<div />' } },
    { path: '/tasks/:id/design', component: { template: '<div />' } },
    { path: '/designer', component: { template: '<div />' } },
    { path: '/projects', component: { template: '<div />' } },
  ] })
  await router.push('/tasks')
  await router.isReady()
})

function mountView() {
  const TableStub = defineComponent({
    props: { data: { type: Array, default: () => [] } },
    setup(props, { slots }) {
      return () => h('div', { class: 'table-stub' }, props.data.map((row) => h('div', { class: 'table-row' },
        (slots.default?.() ?? []).flatMap((column: VNode) => {
          const columnSlots = column.children as Slots | null
          return columnSlots?.default?.({ row }) ?? []
        }))))
    },
  })
  const SelectStub = defineComponent({
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
  })
  const InputStub = defineComponent({
    props: ['modelValue'], emits: ['update:modelValue'],
    template: '<input class="input-stub" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  })
  return mount(TasksView, {
    global: {
      plugins: [router],
      stubs: {
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        MetricCard: true,
        StatusBadge: true,
        Icon: true,
        ElTable: TableStub,
        ElTableColumn: true,
        ElSelect: SelectStub,
        ElInput: InputStub,
        ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
        ElButtonGroup: { template: '<div><slot /></div>' },
        ElButton: { template: '<button><slot /></button>' },
      },
    },
  })
}

describe('Tasks filters and design history', () => {
  it('filters by project and sorts by updated time in both directions', async () => {
    const wrapper = mountView()
    await flushPromises()
    const selects = wrapper.findAll('select')

    expect(wrapper.findAll('.task-link').map((link) => link.text())).toEqual(['A 的新任务', 'B 的任务', 'A 的旧任务'])
    await selects[0]!.setValue('project-a')
    await flushPromises()
    expect(wrapper.findAll('.task-link').map((link) => link.text())).toEqual(['A 的新任务', 'A 的旧任务'])

    await selects[0]!.setValue('ALL')
    await selects[2]!.setValue('OLDEST')
    await flushPromises()
    expect(wrapper.findAll('.task-link').map((link) => link.text())).toEqual(['A 的旧任务', 'B 的任务', 'A 的新任务'])
  })

  it('keeps search, grouping, archive scope and status in the URL', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.input-stub').setValue('B 的任务')
    await flushPromises()
    expect(wrapper.findAll('.task-link').map((link) => link.text())).toEqual(['B 的任务'])
    expect(router.currentRoute.value.query.q).toBe('B 的任务')

    await wrapper.get('.input-stub').setValue('')
    const groupButton = wrapper.findAll('button').find((button) => button.text().includes('按项目分组'))
    await groupButton!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.task-group-header')).toHaveLength(2)
    expect(router.currentRoute.value.query.group).toBe('project')

    await wrapper.get('button[aria-label^="归档任务"]').trigger('click')
    await flushPromises()
    expect(useTaskStore().tasks.filter((task) => task.archived)).toHaveLength(1)
    await wrapper.findAll('select')[1]!.setValue('ARCHIVED')
    await flushPromises()
    expect(wrapper.findAll('.task-link')).toHaveLength(1)
    expect(router.currentRoute.value.query.archive).toBe('archived')
  })

  it('permanently deletes only an archived task after explicit confirmation', async () => {
    const wrapper = mountView()
    await flushPromises()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)

    await wrapper.get('button[aria-label^="归档任务"]').trigger('click')
    await wrapper.findAll('select')[1]!.setValue('ARCHIVED')
    await flushPromises()

    const remove = wrapper.get('button[aria-label^="永久删除任务"]')
    await remove.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('项目文件、Git 分支与 worktree 不会删除'), '永久删除历史任务？', expect.objectContaining({ confirmButtonText: '永久删除' }))
    expect(useTaskStore().tasks).toHaveLength(2)
    expect(wrapper.findAll('.task-link')).toHaveLength(0)
  })

  it('guides first-time users to register a project before opening Designer', async () => {
    const store = useTaskStore()
    store.usingDemo = false
    store.tasks = []
    store.projects = []
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('先登记一个项目')
    const register = wrapper.findAll('button').find((button) => button.text().includes('登记项目'))
    await register!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/projects')
  })

  it('opens the persisted design and LoopSpec history for a task', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.design-history-link')[0]!.text()).toBe('查看')
    await wrapper.findAll('.design-history-link')[0]!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/tasks/new-a/design')
  })
})
