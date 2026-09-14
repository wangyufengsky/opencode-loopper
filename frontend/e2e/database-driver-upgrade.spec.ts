import { expect, test } from '@playwright/test'
import type { DatabaseConnectionInput } from '../src/types/domain'
for (const width of [1440, 390]) {
  test(`openGauss 驱动升级与认证反馈 ${width}px`, async ({ page }) => {
    const row = { id:'fixture', name:'旧业务库', config:{type:'OPENGAUSS',host:'db1',port:8000,database:'app',username:'reader',driverProfile:'opengauss-6.0.3',driverFile:'opengauss-jdbc-6.0.3.jar',driverClass:'org.postgresql.Driver',jdbcUrl:'jdbc:opengauss://db1:8000,db2:8000/app?targetServerType=master',schemas:['public'],parameters:{targetServerType:'master'},timeoutSeconds:10,maxRows:200},passwordConfigured:true,enabled:true,archived:false,projectIds:[],version:2,createdAt:'' }
    const drafts: DatabaseConnectionInput[] = [], saves: DatabaseConnectionInput[] = [], errors: string[] = []
    page.on('pageerror', e => errors.push(e.message))
    await page.route('http://127.0.0.1:41773/api/**', async route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/database-connections/types') return route.fulfill({json:[{type:'OPENGAUSS',label:'openGauss',id:'opengauss-7.0.0-RC3-og',driverClass:'org.opengauss.Driver',defaultPort:5432,binaries:[{filename:'opengauss-jdbc-7.0.0-RC3-og.jar',sha256:'fixture'}]}]})
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
