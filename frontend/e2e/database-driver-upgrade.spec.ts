import { expect, test } from '@playwright/test'
import type { DatabaseConnectionInput } from '../src/types/domain'
for (const width of [1440, 390]) {
  test(`openGauss 驱动升级与认证反馈 ${width}px`, async ({ page }) => {
    const row = { id:'fixture', name:'旧业务库', config:{type:'OPENGAUSS',host:'db1',port:8000,database:'app',username:'reader',driverProfile:'opengauss-6.0.3',driverFile:'opengauss-jdbc-6.0.3.jar',driverClass:'org.postgresql.Driver',jdbcUrl:'jdbc:opengauss://db1:8000,db2:8000/app?targetServerType=master',schemas:['public'],parameters:{targetServerType:'master'},timeoutSeconds:10,maxRows:200},passwordConfigured:true,enabled:true,archived:false,projectIds:[],version:2,createdAt:'' }
    const drafts: DatabaseConnectionInput[] = [], saves: DatabaseConnectionInput[] = [], errors: string[] = []
    page.on('pageerror', e => errors.push(e.message))
    await page.route('http://127.0.0.1:41773/api/**', async route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/database-connections/types') return route.fulfill({json:[{type:'OPENGAUSS',label:'openGauss',id:'opengauss-3.1.0',driverClass:'org.postgresql.Driver',defaultPort:5432,binaries:[{filename:'opengauss-jdbc-7.0.0-RC3-og.jar',sha256:'fixture'}]}]})
      if (path === '/api/database-connections/test') { drafts.push(route.request().postDataJSON()); return route.fulfill({status:400,json:{detail:'数据库拒绝登录。请核对用户名、密码、目标数据库及节点，并与可连接客户端的驱动版本保持一致；这不一定表示密码输入错误',code:'DATABASE_AUTHENTICATION_FAILED'}}) }
      if (path === '/api/database-connections/fixture' && route.request().method() === 'PUT') { saves.push(route.request().postDataJSON()); return route.fulfill({json:row}) }
      if (path === '/api/database-connections') return route.fulfill({json:{items:[row],nextCursor:null}})
      return route.fulfill({json:[]})
    })
    await page.setViewportSize({width,height:1000}); await page.goto('/databases')
    await page.getByRole('button',{name:'编辑',exact:true}).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer.getByText(/opengauss-jdbc-7.0.0-RC3-og.jar/)).toBeVisible()
    await expect(drawer.getByText(/历史任务保留原驱动/)).toBeVisible()
    await drawer.getByRole('button',{name:'测试连接',exact:true}).click()
    await expect(drawer.getByRole('alert')).toContainText('数据库拒绝登录')
    expect(saves).toHaveLength(0)
    await page.screenshot({path:`test-results/gauss-upgrade-${width}.png`})
    await drawer.getByRole('button',{name:'保存连接',exact:true}).click()
    await expect(drawer).not.toBeVisible()
    expect(saves).toHaveLength(1); expect(saves[0]).toEqual(drafts[0])
    expect(saves[0]).toMatchObject({password:null,version:2,config:{driverProfile:null,driverClass:'',driverFile:'',jdbcUrl:row.config.jdbcUrl}})
    expect(errors).toEqual([])
  })
}

for (const width of [1440, 390]) {
  for (const [type,label,url,driverClass,filename,defaultPort] of [
    ['GAUSSDB','GaussDB','jdbc:postgresql://db:8000/app','org.postgresql.Driver','opengauss-jdbc-3.1.0.jar',5432],
    ['ORACLE','Oracle','jdbc:oracle:thin:@//db:1521/service','oracle.jdbc.OracleDriver','ojdbc11-23.7.0.25.01.jar',1521],
    ['DB2','DB2','jdbc:db2://db:50000/app','com.ibm.db2.jcc.DB2Driver','jcc-12.1.0.0.jar',50000],
  ] as const) {
    test(`新增 ${label} 并独立传递密码 ${width}px`,async({page})=>{
      const drafts: DatabaseConnectionInput[] = [], saves: DatabaseConnectionInput[] = []
      await page.route('http://127.0.0.1:41773/api/**',async route=>{
        const path=new URL(route.request().url()).pathname
        if(path==='/api/database-connections/types')return route.fulfill({json:[{type,label,id:'fixture',driverClass,defaultPort,binaries:[{filename,sha256:'fixture'}]}]})
        if(path==='/api/database-connections/test') {drafts.push(route.request().postDataJSON());return route.fulfill({json:{connected:true,sessionReadOnly:true,serverProduct:label,serverVersion:'fixture',driverVersion:'fixture',driverSha256:'fixture',compatibilityVerified:false,detail:'模拟连接反馈'}})}
        if(path==='/api/database-connections' && route.request().method()==='POST') {saves.push(route.request().postDataJSON());return route.fulfill({json:{id:'fixture'}})}
        return route.fulfill({json:path==='/api/database-connections'?{items:[],nextCursor:null}:[]})
      })
      await page.setViewportSize({width,height:1000});await page.goto('/databases')
      await page.getByRole('button',{name:'新增连接',exact:true}).click()
      const drawer=page.getByRole('dialog')
      await drawer.getByPlaceholder('例如：业务只读库').fill('验收连接')
      await drawer.locator('.el-select').first().click()
      await page.getByRole('option',{name:label,exact:true}).click()
      await expect(drawer.getByText(new RegExp(filename.replaceAll('.','\\.')))).toBeVisible()
      await drawer.getByRole('textbox',{name:'JDBC URL'}).fill(url)
      await drawer.getByRole('textbox',{name:'用户名',exact:true}).fill('reader')
      await drawer.locator('input[type="password"]').fill(' p@ss+&=%密 ')
      await drawer.getByPlaceholder('多个名称用逗号分隔').fill('APP')
      await drawer.getByRole('button',{name:'测试连接',exact:true}).click()
      await expect(drawer.getByRole('status')).toContainText('模拟连接反馈')
      await page.screenshot({path:`test-results/database-${type}-${width}.png`})
      await drawer.getByRole('button',{name:'保存连接',exact:true}).click()
      await expect(drawer).not.toBeVisible()
      expect(saves).toHaveLength(1);expect(saves[0]).toEqual(drafts[0])
      expect(saves[0]).toMatchObject({password:' p@ss+&=%密 ',config:{type,jdbcUrl:url,username:'reader'}})
    })
  }
}
