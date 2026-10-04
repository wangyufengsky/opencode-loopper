import { flushPromises, mount } from '@/pages/w6-tests/ordinary/render'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import {DatabasePage as DatabaseConnectionDrawer} from '@/pages/w2/secondary/DatabasePage'
import {coreFixture} from '@/pages/w2/core/coreTestHelpers'
import {fireEvent,act} from '@testing-library/react'
import type { DatabaseConnection, DatabaseTypeProfile, DatabaseProbe } from '@/types/domain'
const types: DatabaseTypeProfile[] = [{ type:'MYSQL', label:'MySQL', id:'mysql-8.0.33', driverClass:'com.mysql.cj.jdbc.Driver', defaultPort:3306, binaries:[{filename:'mysql.jar',sha256:'sha'}] },{ type:'OPENGAUSS',label:'openGauss',id:'opengauss-3.1.0',driverClass:'org.postgresql.Driver',defaultPort:5432,binaries:[{filename:'opengauss-jdbc-7.0.0-RC3-og.jar',sha256:'sha'}] }]
const row: DatabaseConnection = { id:'c',name:'业务库',config:{type:'MYSQL',host:'db',port:3306,database:'app',username:'reader',driverFile:'legacy.jar',driverClass:'legacy.Driver',schemas:['app'],parameters:{},timeoutSeconds:10,maxRows:200},enabled:true,archived:false,passwordConfigured:true,projectIds:[],version:2,createdAt:'' }
afterEach(()=>{vi.restoreAllMocks();document.body.innerHTML=''})
async function mountDrawer(next=row,nextTypes=types){
 vi.spyOn(api,'getDatabaseTypes').mockResolvedValue(nextTypes);vi.spyOn(api,'getDatabaseDrivers').mockResolvedValue([]);vi.spyOn(api,'getProjects').mockResolvedValue([]);vi.spyOn(api,'getDatabaseConnections').mockResolvedValue({items:[next],facets:{}})
 const f=coreFixture();const w=mount(DatabaseConnectionDrawer,{props:f.props});await flushPromises();await w.get('.w2-secondary-select').trigger('click');await w.get('[data-semantic="database.edit"]').trigger('click');return w
}
const change=async(element:Element,value:string)=>{act(()=>fireEvent.change(element,{target:{value}}));await flushPromises()}
const click=async(key:string)=>{act(()=>fireEvent.click(document.querySelector(`[data-semantic="${key}"]`)!));await flushPromises()}
describe('database connection drawer',()=>{
 it.each([
  ['GAUSSDB', 'GaussDB', 'jdbc:postgresql://db:5432/app'],
  ['ORACLE', 'Oracle', 'jdbc:oracle:thin:@//db:1521/app'],
  ['DB2', 'DB2', 'jdbc:db2://db:50000/app'],
 ] as const)('edits and tests %s using the same URL and independently supplied password',async(type,label,url)=>{
  const port=type==='ORACLE'?1521:type==='DB2'?50000:5432
  const old:DatabaseConnection={...row,config:{...row.config,type,port}}
  const profile:DatabaseTypeProfile={type,label,id:'fixture',driverClass:'fixture.Driver',defaultPort:port,binaries:[{filename:'fixture.jar',sha256:'fixture'}]}
  const test=vi.spyOn(api,'testDatabaseDraft').mockRejectedValue(new Error('测试返回'))
  const save=vi.spyOn(api,'saveDatabaseConnection').mockResolvedValue(old)
  const w=await mountDrawer(old,[...types,profile]);await flushPromises()
  expect(document.querySelector('textarea')!.value).toBe(url)
  const password=document.querySelector<HTMLInputElement>('input[type="password"]')!
  await change(password,' 密码+&=% ');await flushPromises()
  await click('database.test')
  await click('ui.save')
  expect(test.mock.calls[0]).toEqual(save.mock.calls[0])
  expect(save).toHaveBeenCalledWith('c',expect.objectContaining({password:' 密码+&=% ',config:expect.objectContaining({type,jdbcUrl:url})}))
  w.unmount()
 })

 it('tests an unsaved draft without storing it and discards a result after input changes',async()=>{
  let finish!: (value:DatabaseProbe)=>void
  const test=vi.spyOn(api,'testDatabaseDraft').mockImplementation(()=>new Promise(resolve=>{finish=resolve}))
  const save=vi.spyOn(api,'saveDatabaseConnection')
  const w=await mountDrawer();await flushPromises()
  await click('database.test')
  expect(test).toHaveBeenCalledWith('c',expect.objectContaining({password:null,version:2,config:expect.objectContaining({driverFile:'',driverClass:''})}))
  const name=document.querySelector('.connection-form input')!;await change(name,'新名称');await flushPromises()
  finish({connected:true,sessionReadOnly:true,serverProduct:'server',serverVersion:'1',driverVersion:'1',driverSha256:'s',compatibilityVerified:false,detail:'旧测试结果'});await flushPromises()
  expect(document.body.textContent).not.toContain('旧测试结果');expect(save).not.toHaveBeenCalled();w.unmount()
 })
 it('shows the driver upgrade and tests and saves the same draft without replacing the saved password',async()=>{
  const old:DatabaseConnection={...row,config:{...row.config,type:'OPENGAUSS',driverProfile:'opengauss-6.0.3',driverFile:'opengauss-jdbc-6.0.3.jar',driverClass:'org.postgresql.Driver',jdbcUrl:'jdbc:opengauss://db1:8000,db2:8000/app?targetServerType=master'}}
  const test=vi.spyOn(api,'testDatabaseDraft').mockRejectedValue(new Error('数据库拒绝登录，请核对驱动和账号'))
  const save=vi.spyOn(api,'saveDatabaseConnection').mockResolvedValue(old)
  const w=await mountDrawer(old);await flushPromises()
  expect(document.body.textContent).toContain('opengauss-jdbc-7.0.0-RC3-og.jar')
  expect(document.body.textContent).toContain('历史任务保留原驱动')
  await click('database.test')
  expect(document.body.textContent).toContain('数据库拒绝登录')
  expect(save).not.toHaveBeenCalled()
  await click('ui.save')
  expect(test.mock.calls[0]).toEqual(save.mock.calls[0])
  expect(save).toHaveBeenCalledWith('c',expect.objectContaining({password:null,version:2,config:expect.objectContaining({driverProfile:null,driverFile:'',driverClass:'',jdbcUrl:old.config.jdbcUrl})}))
  expect(old.config.driverProfile).toBe('opengauss-6.0.3');w.unmount()
 })
 it('converts a legacy address to URL and submits a multi-host URL with credentials separately',async()=>{
  const save=vi.spyOn(api,'saveDatabaseConnection').mockResolvedValue(row)
  const w=await mountDrawer();await flushPromises()
  const url=document.querySelector('textarea')!;expect(url.value).toBe('jdbc:mysql://db:3306/app')
  await w.get('.connection-form select').setValue('OPENGAUSS');await flushPromises()
  await change(url,'jdbc:opengauss://db1:8000,db2:8000/app?targetServerType=master');await flushPromises()
  await click('ui.save')
  expect(save).toHaveBeenCalledWith('c',expect.objectContaining({password:null,config:expect.objectContaining({jdbcUrl:url.value,type:'OPENGAUSS',username:'reader'})}))
  expect(document.body.textContent).not.toContain('厂商驱动类');w.unmount()
 })
})
