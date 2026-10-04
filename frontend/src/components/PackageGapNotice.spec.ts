import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { api } from '@/api/client'
import { DesignerPage } from '@/pages/w5/designer/DesignerPage'
import { createDesignerController } from '@/pages/w5/designer/controller'
import { mockDesigner,session,pageProps } from '@/pages/w5/designer/test-support'
import { flushPromises,mount } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import type { DesignWorkPackageStatus } from '@/types/domain'
vi.mock('@/api/client',async original=>({...await original<typeof import('@/api/client')>(),subscribeDesignerEvents:vi.fn()}))
let owner:ReturnType<typeof createDesignerController>
function value(code:string){const pkg:DesignWorkPackageStatus={id:'WP-1',ordinal:0,title:'测试工作包',objective:'真实投影',dependencies:[],state:'WAITING_INPUT',redesignCount:0,compilerRepairCount:0,compilerPlanningRepairCount:0,designRevision:1,discussionRoundCount:0,lastErrorCode:code,lastErrorDetail:'来源尚未确认'};return session('A',{activeWorkPackageId:'WP-1',workPackages:[pkg]})}
async function render(code:string){mockDesigner(value(code));const {props}=pageProps();props.route={path:'/designer',fullPath:'/designer?sessionId=A',query:{sessionId:'A'},params:{}};owner=createDesignerController({sessionId:'A',historyOnly:true,navigation:props.navigation});const wrapper=mount(DesignerPage,{props:{...props,controller:owner}});await flushPromises();return wrapper}
beforeEach(()=>sessionStorage.clear())
afterEach(()=>{owner?.retire(true);vi.restoreAllMocks()})
describe('PackageGapNotice',()=>{
 it('keeps an unconfirmed claim distinct from a proven business decision',async()=>{const w=await render('PACKAGE_GAP_UNCONFIRMED');expect(w.text()).toContain('不足以确认缺少需求或能力');expect(w.text()).not.toContain('请在本地反馈中明确不同选择');vi.mocked(api.getDesignerSession).mockResolvedValue(value('PACKAGE_GAP_BUSINESS_DECISION'));await owner.refresh();await flushPromises();expect(w.text()).toContain('业务选择待确认');expect(w.text()).toContain('不同选择对应的行为')})
 it('does not manufacture a classification for legacy or unknown codes',async()=>{const w=await render('PACKAGE_DESIGN_NEEDS_INPUT');expect(w.find('aside[aria-label="工作包待处理事项"]').exists()).toBe(false)})
})
