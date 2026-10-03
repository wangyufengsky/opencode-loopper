import type { KnowledgeConversation,KnowledgeMessage,KnowledgeQuestion,KnowledgeSource } from '@/types/domain'
export const sourceFixture:KnowledgeSource={id:'code',kind:'CODE',name:'项目代码',state:'READY',detail:'已索引',version:2}
export const conversationFixture=(id='conversation'):KnowledgeConversation=>({id,projectId:'p',title:'项目问题',model:'deepseek/shared-model',state:'IDLE',sources:[sourceFixture],createdAt:'now',updatedAt:'now',version:3})
export const messageFixture=(ordinal=1,answer=''):KnowledgeMessage=>({id:`m${ordinal}`,ordinal,state:'COMPLETED',userText:'核心流程是什么？',answer,thinking:'',detail:'',inputTokens:null,outputTokens:null,createdAt:'now',citations:[],calls:[]})
export const questionFixture:KnowledgeQuestion={id:'question',state:'PENDING',version:7,answers:[],questions:[{question:'范围？',header:'范围',multiple:true,custom:true,options:[{label:'代码',description:'当前实现'},{label:'文档',description:'需求原文'}]}]}
