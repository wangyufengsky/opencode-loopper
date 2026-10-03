import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type RefObject } from 'react'

/** Local preference and capture belong to this drawer; cancellation never writes a server command. */
export function useEvidenceWidth(workspace: RefObject<HTMLElement | null>) {
  const [width,setWidth]=useState(() => {try {const value=Number(localStorage.getItem('loopper.knowledge.drawerWidth'));return value>=320 && value<=1000 ? value : 480}catch{return 480}})
  const [maximum,setMaximum]=useState(1000),[dragging,setDragging]=useState(false)
  const session=useRef<{target:HTMLElement;pointer:number;start:number;width:number} | undefined>(undefined), latest=useRef(width)
  latest.current=width
  const save=(value:number) => {try {localStorage.setItem('loopper.knowledge.drawerWidth',String(value))}catch{/* Optional preference. */}}
  const change=(value:number) => {const next=Math.round(Math.max(320,Math.min(maximum,value)));latest.current=next;setWidth(next);return next}
  function stop(persist=false) {const current=session.current;if (!current)return;session.current=undefined;setDragging(false);try {if(current.target.hasPointerCapture(current.pointer))current.target.releasePointerCapture(current.pointer)}catch{/* Already lost capture. */}if(persist)save(latest.current)}
  useEffect(() => {
    const measure=() => setMaximum(Math.max(320,Math.min(1000,(workspace.current?.getBoundingClientRect().width || window.innerWidth)*.85)))
    measure();const observer=new ResizeObserver(measure);if(workspace.current)observer.observe(workspace.current);window.addEventListener('resize',measure)
    const blur=() => stop(false);window.addEventListener('blur',blur)
    return () => {stop(false);observer.disconnect();window.removeEventListener('resize',measure);window.removeEventListener('blur',blur)}
  },[workspace])
  function start(event:PointerEvent<HTMLElement>) {if(event.button!==0 || session.current)return;const target=event.currentTarget;try {target.setPointerCapture(event.pointerId);if(!target.hasPointerCapture(event.pointerId))return}catch{return}event.preventDefault();session.current={target,pointer:event.pointerId,start:event.clientX,width:Math.min(width,maximum)};setDragging(true)}
  function move(event:PointerEvent<HTMLElement>) {const current=session.current;if(current?.pointer===event.pointerId)change(current.width+current.start-event.clientX)}
  function keyboard(event:KeyboardEvent<HTMLElement>) {if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;event.preventDefault();stop(false);save(change(event.key==='Home' ? 320 : event.key==='End' ? maximum : Math.min(width,maximum)+(event.key==='ArrowLeft' ? 32 : -32)))}
  return {width:Math.round(Math.min(width,maximum)),maximum,dragging,start,move,keyboard,stop,reset(){stop(false);save(change(480))}}
}
