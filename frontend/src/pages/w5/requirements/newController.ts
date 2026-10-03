import { workflowApi } from '@/api/workflow'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowReceipt } from '@/types/domain'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { createRequirementScope, ownedState } from './core'

export interface NewRequirementState {
  title: string; objective: string; project: { id: string; title: string } | null; flow: { id: string; title: string; revision: number } | null
  projectError: string; flowError: string; knownId: string
}
export function createNewRequirementController(options: { template?: string; projectId?: string; legacyDraft?: string; navigation: W2PageProps['navigation'] }) {
  const owner = createRequirementScope('new-requirement', 'new', { ...ownedState(), title: '', objective: options.legacyDraft ?? '', project: null, flow: null, projectError: '', flowError: '', knownId: '' } as ReturnType<typeof ownedState> & NewRequirementState,
    state => state.knownId ? { kind: 'ALLOW' } : state.dirty || state.title || state.objective || state.project ? { kind: 'CONFIRM_DISCARD', draftRevision: state.draftRevision, description: '需求内容尚未保存，仍要离开？' } : { kind: 'ALLOW' })
  const initialTemplate = options.template || 'builtin.workflow.development'
  async function load() {
    const ticket = owner.ticket('initial'); owner.patch({ loading: true })
    const [flow, project] = await Promise.allSettled([workflowApi.get(initialTemplate), options.projectId ? workflowRuns.project(options.projectId) : Promise.resolve(null)])
    if (!ticket.current()) return
    const previous = owner.getSnapshot()
    owner.patch({ loading: false,
      flow: flow.status === 'fulfilled' && !previous.flow ? { id: flow.value.id, title: flow.value.title, revision: flow.value.revision } : previous.flow,
      project: project.status === 'fulfilled' && project.value && !previous.project ? { id: project.value.id, title: project.value.name } : previous.project,
      flowError: flow.status === 'rejected' ? '指定流程无法读取，请重新选择。' : '', projectError: project.status === 'rejected' ? '指定项目无法读取，请重新选择。' : '',
    })
  }
  owner.setStart(() => { void load() })
  const valid = () => { const state = owner.getSnapshot(); return !!state.project && !!state.flow?.revision && !!state.title.trim() && !!state.objective.trim() }
  async function create() {
    const state = owner.getSnapshot()
    if (!valid() || owner.locked() || state.loading || state.knownId) return
    const body = { requestKey: crypto.randomUUID(), projectId: state.project!.id, templateId: state.flow!.id, templateRevision: state.flow!.revision, title: state.title.trim(), objective: state.objective.trim() }
    await owner.mutate<typeof body, WorkflowReceipt>({ label: '创建需求', input: { endpoint: '/workflows/requirements', method: 'POST', requestKey: body.requestKey, body, versions: { templateRevision: body.templateRevision } },
      write: original => workflowRuns.create(original),
      handoffTarget: receipt => `/requirements/${encodeURIComponent(receipt.id)}`,
      read: async (receipt, context) => {
        if (!receipt.id || !Number.isInteger(receipt.revision)) throw new Error('创建回执无法核对，请保留原操作。')
        if (!context.apply(() => owner.patch({ knownId: receipt.id, dirty: false }))) return
        const permit = owner.prepareHandoff()
        if (!await options.navigation.goAccepted(permit.destination, permit)) throw new Error('需求已创建，页面尚未打开，请打开已创建的需求。')
      },
    })
  }
  return Object.assign(owner, { load, valid, create,
    change(values: Partial<Pick<NewRequirementState, 'title' | 'objective'>>) { owner.edit(values) },
    selectProject(value: NonNullable<NewRequirementState['project']>) { owner.edit({ project: value, projectError: '' }) },
    selectFlow(value: NonNullable<NewRequirementState['flow']>) { owner.edit({ flow: value, flowError: '' }) },
  })
}
