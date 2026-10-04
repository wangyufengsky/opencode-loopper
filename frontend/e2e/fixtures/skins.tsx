/** Isolated React skin contract fixture; production components, simulated content. */
import { useState, useSyncExternalStore } from 'react'
import { createRoot } from 'react-dom/client'
import { Modal, Button, Input, Tag } from 'antd'
import '@/styles/tokens.css'
import '@/styles/app.css'
import { FoundationProvider, useFoundationContainer } from '@/foundation/provider'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { MergeEditor } from '@/pages/w4/publication/MergeEditor'
import { initializeSkin, getSkinSnapshot, subscribeSkin, applySkin } from '@/themes/state'
import { skins } from '@/themes/registry'
const markdown = '# 文档预览\n\n**重点文字**、[参考链接](https://example.com)、`行内代码`。\n\n> 引用内容\n\n| 状态 | 说明 |\n| --- | --- |\n| 就绪 | 可开始 |\n\n```java\nString name = "Loopper";\n```\n\n```mermaid\nflowchart LR\nA[需求] --> B[设计] --> C[交付]\n```'
function Content(){
 const skin=useSyncExternalStore(subscribeSkin,getSkinSnapshot,getSkinSnapshot),container=useFoundationContainer()
 const [text,setText]=useState('未保存的输入'),[code,setCode]=useState('public class Skin {\n  // 保留内容和光标\n  String name = "Loopper";\n}'),[dialog,setDialog]=useState(false),[choice,setChoice]=useState('一')
 return <main className="content" style={{maxWidth:1000}}><div className="toolbar"><h1>皮肤验收</h1><select aria-label="选择皮肤" value={skin.id} onChange={e=>applySkin(e.target.value)}>{skins.map(s=><option key={s.id} value={s.id}>{s.label}</option>)}</select></div>
 <section className="card card-pad"><div className="toolbar-group" style={{flexWrap:'wrap',marginBottom:16}}><Button>普通操作</Button><Button type="primary" onClick={()=>setDialog(true)}>打开弹窗</Button><Button>成功</Button><Button>警告</Button><Button danger>危险</Button><Button disabled>不可用</Button></div><Input value={text} onChange={e=>setText(e.target.value)} aria-label="未保存输入"/><select value={choice} onChange={e=>setChoice(e.target.value)} aria-label="示例选项"><option>一</option><option>二</option></select><div className="toolbar-group" style={{marginTop:16}}>{['RUNNING','SUCCEEDED','WAITING_INPUT','FAILED'].map(state=><Tag key={state}>{state}</Tag>)}</div></section>
 <section className="card card-pad" style={{marginTop:20}}><RichDocument content={markdown} skin={skin}/></section><section className="card" style={{marginTop:20}}><MergeEditor value={code} baseline={code} path="Skin.java" activeIndex={0} onChange={setCode}/></section>
 {dialog && <Modal open title="皮肤弹窗" width={480} getContainer={container} onCancel={()=>setDialog(false)} footer={<Button onClick={()=>setDialog(false)}>关闭</Button>}><p>弹窗继承全局皮肤。</p><Input value={text} onChange={e=>setText(e.target.value)} aria-label="弹窗输入"/></Modal>}</main>
}
function Preview(){const skin=useSyncExternalStore(subscribeSkin,getSkinSnapshot,getSkinSnapshot);return <FoundationProvider skin={skin}><Content/></FoundationProvider>}
const stop=initializeSkin(),root=createRoot(document.getElementById('app')!);root.render(<Preview/>);if(import.meta.hot)import.meta.hot.dispose(()=>{root.unmount();stop()})
