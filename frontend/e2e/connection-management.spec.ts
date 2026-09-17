import { expect, test } from '@playwright/test'

const profile = {type:'SQLSERVER',label:'SQL Server',id:'sqlserver-13.4.0.jre11',driverClass:'com.microsoft.sqlserver.jdbc.SQLServerDriver',defaultPort:1433,binaries:[{filename:'mssql-jdbc-13.4.0.jre11.jar',sha256:'fixture'}]}
const connection = {id:'one',name:'客户业务只读库',config:{type:'SQLSERVER',host:'sqlserver.internal',port:1433,database:'customer',username:'report_reader',driverProfile:profile.id,driverFile:profile.binaries[0].filename,driverClass:profile.driverClass,jdbcUrl:'jdbc:sqlserver://sqlserver.internal:1433;databaseName=customer;encrypt=true;trustServerCertificate=false',schemas:['dbo','reporting'],parameters:{},timeoutSeconds:10,maxRows:200},passwordConfigured:true,enabled:true,archived:false,projectIds:['p'],version:1,createdAt:''}
for (const width of [1440,1024,768,390]) {
  test(`连接管理工作区与统一 Git 入口 ${width}px`,async({page})=>{
    const errors: string[]=[];page.on('pageerror', e=>errors.push(e.message))
    let saved=0
    await page.route('http://127.0.0.1:41773/api/**',async route=>{
      const path=new URL(route.request().url()).pathname
      if(path==='/api/database-connections/types')return route.fulfill({json:[profile]})
      if(path==='/api/database-connections')return route.fulfill({json:{items:[connection,{...connection,id:'two',name:'归档测试库',archived:true,enabled:false}],nextCursor:null}})
      if(path==='/api/database-connections/one/test')return route.fulfill({json:{connected:true,sessionReadOnly:false,readOnlyEnforced:true,serverProduct:'SQL Server',serverVersion:'2022',detail:'模拟只读权限检查完成'}})
      if(path==='/api/database-connections/two/test')return route.fulfill({status:400,json:{detail:'连接检查失败，请检查网络'}})
      if(path==='/api/projects/summaries')return route.fulfill({json:[{id:'p',name:'客户服务项目',rootPath:'/projects/customer',status:'READY',taskCount:0,openDesignerSessionCount:0}]})
      if(path==='/api/projects/p/assist-config')return route.fulfill({json:{version:1,credentialConfigured:true,config:{repository:'team/customer',instance:'http://gitlab.internal/api/v4',sources:[]}}})
      if(path==='/api/git-credentials'){
        if(route.request().method()==='PUT')saved++
        return route.fulfill({json:{mode:'CUSTOM',source:'PROJECT',serverUrl:'http://gitlab.internal',username:'reader',kind:'TOKEN',configured:true,version:1}})
      }
      return route.fulfill({json:[]})
    })
    await page.setViewportSize({width,height:1000});await page.goto('/databases')
    await expect(page.getByRole('heading',{name:'客户业务只读库',exact:true})).toBeVisible()
    await page.getByRole('button',{name:'测试连接',exact:true}).click()
    await expect(page.getByRole('status')).toContainText('只读权限已检查')
    await page.getByRole('button',{name:/归档测试库/}).click()
    await expect(page.getByRole('button',{name:'编辑连接',exact:true})).toBeDisabled()
    await page.getByRole('button',{name:'测试连接',exact:true}).click()
    await expect(page.getByRole('alert')).toContainText('请检查网络')
    await page.getByRole('button',{name:/客户业务只读库/}).click()
    await expect(page.getByRole('status')).toContainText('模拟只读权限检查完成')
    expect(await page.locator('body').evaluate(n=>n.scrollWidth<=window.innerWidth)).toBeTruthy()
    await page.screenshot({path:`test-results/connection-workspace-${width}.png`,fullPage:true})
    await page.goto('/projects');await page.getByRole('button',{name:'Git 与 GitLab',exact:true}).click()
    const dialog=page.getByRole('dialog')
    await expect(dialog.getByRole('textbox',{name:'Git 服务器地址',exact:true})).toHaveValue('http://gitlab.internal')
    await expect(dialog.getByText(/当前地址使用 HTTP/)).toBeVisible()
    await dialog.getByRole('button',{name:'保存 Git 账号',exact:true}).click()
    await expect(dialog.getByText(/Git 账号已保存/)).toBeVisible();expect(saved).toBe(1)
    await dialog.getByRole('tab',{name:'GitLab 与证据',exact:true}).click()
    await expect(dialog.getByRole('textbox',{name:'GitLab 仓库',exact:true})).toHaveValue('team/customer')
    await expect(dialog.getByText('支持 HTTP 与 HTTPS。')).toBeVisible()
    await page.screenshot({path:`test-results/project-git-${width}.png`,fullPage:true})
    expect(errors).toEqual([])
  })
}
