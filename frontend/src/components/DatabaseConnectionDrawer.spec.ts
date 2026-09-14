import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import DatabaseConnectionDrawer from './DatabaseConnectionDrawer.vue'
import type { DatabaseConnection, DatabaseTypeProfile, DatabaseProbe } from '@/types/domain'
const types: DatabaseTypeProfile[] = [{ type:'MYSQL', label:'MySQL', id:'mysql-8.0.33', driverClass:'com.mysql.cj.jdbc.Driver', defaultPort:3306, binaries:[{filename:'mysql.jar',sha256:'sha'}] },{ type:'OPENGAUSS',label:'openGauss',id:'opengauss-7.0.0-RC3-og',driverClass:'org.opengauss.Driver',defaultPort:5432,binaries:[{filename:'opengauss-jdbc-7.0.0-RC3-og.jar',sha256:'sha'}] }]
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
 it('shows the driver upgrade and tests and saves the same draft without replacing the saved password',async()=>{
  const old:DatabaseConnection={...row,config:{...row.config,type:'OPENGAUSS',driverProfile:'opengauss-6.0.3',driverFile:'opengauss-jdbc-6.0.3.jar',driverClass:'org.postgresql.Driver',jdbcUrl:'jdbc:opengauss://db1:8000,db2:8000/app?targetServerType=master'}}
  const test=vi.spyOn(api,'testDatabaseDraft').mockRejectedValue(new Error('数据库拒绝登录，请核对驱动和账号'))
  const save=vi.spyOn(api,'saveDatabaseConnection').mockResolvedValue(old)
  const w=mountDrawer();await w.setProps({row:old,modelValue:true});await flushPromises()
  expect(document.body.textContent).toContain('opengauss-jdbc-7.0.0-RC3-og.jar')
  expect(document.body.textContent).toContain('历史任务保留原驱动')
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.includes('测试连接'))!.click();await flushPromises()
  expect(document.body.textContent).toContain('数据库拒绝登录')
  expect(save).not.toHaveBeenCalled()
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.includes('保存连接'))!.click();await flushPromises()
  expect(test.mock.calls[0]).toEqual(save.mock.calls[0])
  expect(save).toHaveBeenCalledWith('c',expect.objectContaining({password:null,version:2,config:expect.objectContaining({driverProfile:null,driverFile:'',driverClass:'',jdbcUrl:old.config.jdbcUrl})}))
  expect(old.config.driverProfile).toBe('opengauss-6.0.3');w.unmount()
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
