import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import DatabaseConnectionDrawer from './DatabaseConnectionDrawer.vue'
import type { DatabaseConnection, DatabaseTypeProfile, DatabaseProbe } from '@/types/domain'
const types: DatabaseTypeProfile[] = [{ type:'MYSQL', label:'MySQL', id:'mysql-8.0.33', driverClass:'com.mysql.cj.jdbc.Driver', defaultPort:3306, binaries:[{filename:'mysql.jar',sha256:'sha'}] },{ type:'OPENGAUSS',label:'openGauss',id:'og',driverClass:'org.postgresql.Driver',defaultPort:5432,binaries:[] }]
const row: DatabaseConnection = { id:'c',name:'业务库',config:{type:'MYSQL',host:'db',port:3306,database:'app',username:'reader',driverFile:'legacy.jar',driverClass:'legacy.Driver',schemas:['app'],parameters:{},timeoutSeconds:10,maxRows:200},enabled:true,archived:false,passwordConfigured:true,projectIds:[],version:2,createdAt:'' }
afterEach(()=>{vi.restoreAllMocks();document.body.innerHTML=''})
const mountDrawer=()=>mount(DatabaseConnectionDrawer,{props:{modelValue:false,row,types,projects:[]},global:{plugins:[ElementPlus]},attachTo:document.body})
describe('database connection drawer',()=>{
 it('tests an unsaved draft without storing it and discards a result after input changes',async()=>{
  let finish!: (value:DatabaseProbe)=>void
  const test=vi.spyOn(api,'testDatabaseDraft').mockImplementation(()=>new Promise(resolve=>{finish=resolve}))
  const save=vi.spyOn(api,'saveDatabaseConnection')
  const w=mountDrawer();await w.setProps({modelValue:true});await flushPromises()
  const buttons=()=>Array.from(document.querySelectorAll('button'))
  buttons().find(b=>b.textContent?.includes('测试连接'))!.click();await flushPromises()
  expect(test).toHaveBeenCalledWith('c',expect.objectContaining({password:null,version:2,config:expect.objectContaining({driverFile:'',driverClass:''})}))
  const name=document.querySelector('input')!;name.value='新名称';name.dispatchEvent(new Event('input',{bubbles:true}));await flushPromises()
  finish({connected:true,sessionReadOnly:true,serverProduct:'server',serverVersion:'1',driverVersion:'1',driverSha256:'s',compatibilityVerified:false,detail:'旧测试结果'});await flushPromises()
  expect(document.body.textContent).not.toContain('旧测试结果');expect(save).not.toHaveBeenCalled();w.unmount()
 })
 it('converts a legacy address to URL and submits a multi-host URL with credentials separately',async()=>{
  const save=vi.spyOn(api,'saveDatabaseConnection').mockResolvedValue(row)
  const w=mountDrawer();await w.setProps({modelValue:true});await flushPromises()
  const url=document.querySelector('textarea')!;expect(url.value).toBe('jdbc:mysql://db:3306/app')
  const select=w.findAllComponents({name:'ElSelect'})[0]!;select.vm.$emit('update:modelValue','OPENGAUSS');select.vm.$emit('change','OPENGAUSS');await flushPromises()
  url.value='jdbc:opengauss://db1:8000,db2:8000/app?targetServerType=master';url.dispatchEvent(new Event('input',{bubbles:true}));await flushPromises()
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.includes('保存连接'))!.click();await flushPromises()
  expect(save).toHaveBeenCalledWith('c',expect.objectContaining({password:null,config:expect.objectContaining({jdbcUrl:url.value,type:'OPENGAUSS',username:'reader'})}))
  expect(document.body.textContent).not.toContain('厂商驱动类');w.unmount()
 })
})
