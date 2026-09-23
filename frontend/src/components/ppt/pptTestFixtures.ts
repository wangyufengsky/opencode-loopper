import type {
  PptDocument,
  PptDeck,
  PptCapabilities,
  PptAgentStatus,
  PptPlan,
  PptElement,
} from '@/types/domain'
export const pptDocument = (id = 'doc'): PptDocument => ({
  id,
  title: '季度汇报',
  projectId: null,
  model: 'local/model',
  phase: 'REVIEW',
  revision: 3,
  version: 3,
  archived: false,
  createdAt: '2026-09-22T00:00:00Z',
  updatedAt: '2026-09-22T00:00:00Z',
})
export const pptText = (): PptElement => ({
  id: 'text-1',
  type: 'text',
  x: 80,
  y: 70,
  width: 400,
  height: 90,
  text: '本季度核心成果',
  fontFamily: 'Noto Sans CJK SC',
  fontSize: 32,
  color: '172554',
  bold: false,
  align: 'left',
  rotation: 0,
  locked: false,
  allowOverlap: false,
  rows: [],
  bullets: false,
})
export const pptDeck = (): PptDeck => ({
  title: '季度汇报',
  width: 960,
  height: 540,
  theme: 'business',
  slides: [
    {
      id: 'slide-1',
      title: '核心成果',
      section: '业务收益',
      notes: '介绍成果',
      locked: false,
      elements: [pptText()],
    },
    {
      id: 'slide-2',
      title: '下一步计划',
      section: '后续计划',
      notes: '',
      locked: true,
      elements: [],
    },
  ],
})
export const pptPlan = (): PptPlan => ({
  brief: {
    audience: '管理层',
    purpose: '汇报成果',
    duration: '15 分钟',
    pageCount: 10,
    requirements: '以数据为依据',
  },
  directions: [
    {
      id: 'direction-1',
      title: '业务价值',
      description: '先成果，后计划',
    },
  ],
  selectedDirectionId: 'direction-1',
  slides: [
    {
      id: 'slide-1',
      title: '核心成果',
      section: '业务收益',
      message: '完成季度目标',
      content: '成果一\n成果二',
      layout: 'title_content',
      sourceIds: [],
      notes: '介绍成果',
    },
  ],
  theme: 'business',
})
export const pptCapabilities = (): PptCapabilities => ({
  maxSlides: 100,
  maxElementsPerSlide: 100,
  maxOperations: 100,
  elementTypes: ['text', 'image', 'shape', 'table', 'chart', 'line'],
  chartTypes: ['bar', 'line', 'pie'],
  layouts: ['title_content', 'two_columns'],
  themes: [
    {
      id: 'business',
      name: '商务蓝',
      background: 'FFFFFF',
      foreground: '172554',
      accent: '2563EB',
      muted: '64748B',
      palette: ['2563EB'],
    },
  ],
  fonts: ['Noto Sans CJK SC'],
  renderingAvailable: true,
})
export const pptAgent = (): PptAgentStatus => ({
  state: 'IDLE',
  detail: '',
  version: 0,
  questions: [],
})
export const pptGeneration = (
  state: import('@/types/domain').PptGenerationState = 'PLANNING',
): import('@/types/domain').PptGeneration => ({
  id: 'generation-1',
  documentId: 'doc',
  state,
  step: 'PLANNING',
  detail: '正在整理演示内容',
  revision: 3,
  version: 1,
  runId: null,
  jobId: null,
  canResume: state === 'STOPPED' || state === 'FAILED',
  createdAt: '2026-09-22T00:00:00Z',
  updatedAt: '2026-09-22T00:00:00Z',
  requirementsConfirmed: false,
})
