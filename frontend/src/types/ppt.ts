export const PPT_PHASES = [
  'BRIEFING',
  'DIRECTION',
  'DESIGN',
  'PRODUCING',
  'REVIEW',
  'EXPORTED',
] as const
export type PptPhase = (typeof PPT_PHASES)[number]
export interface PptDocument {
  id: string
  title: string
  projectId: string | null
  model: string
  phase: PptPhase
  revision: number
  version: number
  archived: boolean
  createdAt: string
  updatedAt: string
}
export interface PptProjectChoice {
  id: string
  name: string
}
export interface PptKnowledge {
  project: PptProjectChoice | null
  sources: { id: string; kind: string; name: string; state: string; detail: string; version: number }[]
  detail: string
}
export interface PptDeck {
  title: string
  width: number
  height: number
  theme: string
  slides: PptSlide[]
}
export interface PptSlide {
  id: string
  title: string
  section: string
  notes: string
  locked: boolean
  elements: PptElement[]
  theme?: string | null
}
export interface PptElement {
  id: string
  type: string
  x: number
  y: number
  width: number
  height: number
  text: string
  assetId?: string | null
  fontFamily?: string | null
  fontSize?: number | null
  color?: string | null
  fill?: string | null
  bold: boolean
  align?: string | null
  fit?: string | null
  shape?: string | null
  rotation: number
  locked: boolean
  allowOverlap: boolean
  theme?: string | null
  groupId?: string | null
  rows: string[][]
  chart?: PptChart | null
  bullets: boolean
  stroke?: string | null
  lineWidth?: number | null
}
export interface PptChart {
  type: string
  categories: string[]
  series: {
    name: string
    values: number[]
    color?: string
  }[]
}
export interface PptOperation {
  op: string
  slideId?: string
  elementId?: string
  slide?: Partial<PptSlide>
  element?: Partial<PptElement>
  patch?: Partial<PptSlide> | Partial<PptElement>
  index?: number
  clientRef?: string
  theme?: string
  layout?: string
  elementIds?: string[]
  alignment?: string
  axis?: string
  groupId?: string
}
export interface PptOperationResult {
  revision: number
  deck: PptDeck
  createdIds: Record<string, string>
}
export interface PptPlanSlide {
  id: string
  title: string
  section: string
  message: string
  content: string
  layout: string
  sourceIds: string[]
  notes: string
}
export interface PptPlan {
  brief: {
    audience: string
    purpose: string
    duration: string
    pageCount: number
    requirements: string
    [key: string]: unknown
  }
  directions: {
    id: string
    title: string
    description: string
    story?: string
    chapters?: string
    pageCount?: number
    visual?: string
    [key: string]: unknown
  }[]
  selectedDirectionId: string
  slides: PptPlanSlide[]
  theme: string
  narrative?: PptNarrative
  visualRules?: PptVisualRules
  assets?: PptAssetPlan
  delivery?: PptDelivery
  [key: string]: unknown
}
export interface PptNarrative {
  story: string
  chapters: {
    id: string
    title: string
    purpose: string
    pageCount: number
    [key: string]: unknown
  }[]
  [key: string]: unknown
}
export interface PptVisualRules {
  style: string
  fontFamily: string
  accentColor: string
  density: string
  aspectRatio: string
  [key: string]: unknown
}
export interface PptAssetPlan {
  requirements: string
  chartGuidance: string
  imageGuidance: string
  [key: string]: unknown
}
export interface PptDelivery {
  fileName: string
  targetSoftware: string
  includeNotes: boolean
  [key: string]: unknown
}
export type PptEditablePlan = PptPlan & {
  narrative: PptNarrative
  visualRules: PptVisualRules
  assets: PptAssetPlan
  delivery: PptDelivery
}
export interface PptIssue {
  severity: string
  code: string
  slideId: string
  elementId: string
  message: string
}
export interface PptCapabilities {
  maxSlides: number
  maxElementsPerSlide: number
  maxOperations: number
  elementTypes: string[]
  chartTypes: string[]
  layouts: string[]
  themes: {
    id: string
    name: string
    background: string
    foreground: string
    accent: string
    muted: string
    palette: string[]
  }[]
  fonts: string[]
  renderingAvailable: boolean
  renderingMessage?: string
}
export interface PptSource {
  id: string
  kind: string
  name: string
  mediaType: string
  bytes: number
  sha256: string
  state: string
  detail: string
  createdAt: string
  sections: number
  limitations: string[]
  url?: string
}
export interface PptSourceContent {
  id: string
  name: string
  format: string
  sections: {
    id: string
    title: string
    markdown: string
  }[]
  limitations: string[]
}
export type PptAsset = PptSource
export interface PptArtifact {
  id: string
  slideId?: string | null
  name: string
  mediaType: string
  url: string
}
export interface PptJob {
  id: string
  documentId: string
  kind: 'PREVIEW' | 'EXPORT'
  revision: number
  slideId?: string | null
  state: string
  completed: number
  total: number
  detail: string
  artifacts: PptArtifact[]
  createdAt: string
}
export interface PptRevision {
  revision: number
  title?: string
  reason?: string
  createdAt: string
}
export type PptScope = {
  kind: 'DOCUMENT' | 'SECTION' | 'SLIDE' | 'ELEMENT'
  section?: string
  slideId?: string
  elementId?: string
}
export const PPT_RUN_STATES = [
  'IDLE',
  'PREPARED',
  'CREATING',
  'CREATE_UNKNOWN',
  'SENDING',
  'UNKNOWN',
  'RUNNING',
  'STOPPING',
  'WAITING_INPUT',
  'COMPLETED',
  'STOPPED',
  'FAILED',
] as const
export type PptRunState = (typeof PPT_RUN_STATES)[number]
export interface PptQuestion {
  id: string
  kind?: 'CLARIFICATION' | 'REQUIREMENTS_CONFIRMATION'
  confirmed?: boolean | null
  prompt: string
  options: string[]
  state: 'PENDING' | 'ANSWERED' | 'CLOSED'
  answer: string | null
  version: number
}
export interface PptAgentStatus {
  requirementsState?: 'NOT_REQUIRED' | 'CLARIFYING' | 'AWAITING_CONFIRMATION' | 'CONFIRMED'
  state: PptRunState
  runId?: string
  detail: string
  version: number
  questions: PptQuestion[]
}
export interface PptMessage {
  thinking?: string
  calls?: { id: string; tool: string; state: string; detail: string }[]
  id: string
  documentId: string
  idempotencyKey: string
  text: string
  answer: string
  state: PptRunState
  detail: string
  scope: PptScope
  expectedRevision: number
  version: number
  createdAt: string
  updatedAt: string
  questions: PptQuestion[]
}
export function emptyPptPlan(): PptPlan {
  return {
    brief: {
      audience: '',
      purpose: '',
      duration: '',
      pageCount: 10,
      requirements: '',
    },
    directions: [],
    selectedDirectionId: '',
    slides: [],
    theme: 'business',
  }
}
export function newPptElement(type: string): PptElement {
  return {
    id: crypto.randomUUID(),
    type,
    x: 72,
    y: 72,
    width: type === 'text' ? 520 : 300,
    height: type === 'text' ? 100 : 220,
    text: type === 'text' ? '输入文字' : '',
    fontSize: 24,
    fontFamily: 'Noto Sans CJK SC',
    color: '172554',
    fill: type === 'shape' ? 'DBEAFE' : null,
    bold: false,
    align: 'left',
    fit: 'contain',
    shape: 'rect',
    rotation: 0,
    locked: false,
    allowOverlap: false,
    rows:
      type === 'table'
        ? [
            ['项目', '说明'],
            ['内容', '数据'],
          ]
        : [],
    chart:
      type === 'chart'
        ? {
            type: 'bar',
            categories: ['第一项', '第二项'],
            series: [
              {
                name: '数值',
                values: [30, 50],
                color: '2563EB',
              },
            ],
          }
        : null,
    bullets: false,
  }
}

export const PPT_GENERATION_STATES = [
  'PLANNING',
  'PRODUCING',
  'PREVIEW',
  'EXPORT',
  'WAITING_INPUT',
  'STOPPING',
  'STOPPED',
  'FAILED',
  'COMPLETED',
] as const
export type PptGenerationState = (typeof PPT_GENERATION_STATES)[number]
export type PptGenerationStep = 'PLANNING' | 'PRODUCING' | 'PREVIEW' | 'EXPORT'
export interface PptGeneration {
  id: string
  documentId: string
  state: PptGenerationState
  step: PptGenerationStep
  detail: string
  revision: number
  version: number
  runId: string | null
  jobId: string | null
  canResume: boolean
  createdAt: string
  updatedAt: string
}
