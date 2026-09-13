import { expect, test } from '@playwright/test'
const types = [
  { type:'MYSQL', label:'MySQL', id:'mysql-8.0.33', driverClass:'com.mysql.cj.jdbc.Driver', defaultPort:3306, binaries:[{filename:'mysql-connector-j-8.0.33.jar',sha256:'fixture'}] },
  { type:'OPENGAUSS', label:'openGauss', id:'opengauss-6.0.3', driverClass:'org.postgresql.Driver', defaultPort:5432, binaries:[{filename:'opengauss-jdbc-6.0.3.jar',sha256:'fixture'}] },
  { type:'DAMENG', label:'达梦', id:'dameng-8.1.3.140', driverClass:'dm.jdbc.driver.DmDriver', defaultPort:5236, binaries:[{filename:'DmJdbcDriver18-8.1.3.140.jar',sha256:'fixture'}] },
]
for (const width of [1440,390]) {
 test(`数据库分区配置与真实步骤投影 ${width}px`,async({page})=>{
  let tests=0, saves=0
  await page.route('http://127.0.0.1:41773/api/**',async route=>{
   const path=new URL(route.request().url()).pathname
   if(path==='/api/database-connections/types')return route.fulfill({json:types})
   if(path==='/api/database-connections/test'){tests++;return route.fulfill({json:{connected:true,sessionReadOnly:true,serverProduct:'测试服务器',serverVersion:'8.0',driverVersion:'8.0.33',detail:'连接与只读标记已检查'}})}
   if(path==='/api/database-connections'&&route.request().method()==='POST'){saves++;return route.fulfill({json:{id:'created'}})}
   if(path==='/api/database-connections')return route.fulfill({json:{items:[],nextCursor:null}})
   if(path.endsWith('/overview'))return route.fulfill({json:{id:'fixture',projectId:'p',title:'项目人员贡献周报',status:'RUNNING',loopRetryAvailable:false,cancellationAvailable:false,hasDesignHistory:false,archived:false,executionMode:'TEMPLATE_REPORT',stages:[],templateProgress:{reviewBatches:10,contributorBatches:4,completedReviews:10,completedContributors:2,activeBatches:1,failedBatches:0,repairRound:0,documentPath:'/reports/贡献周报_20260901_001',currentPhase:'CONTRIBUTORS',steps:[{key:'COLLECT',label:'采集提交',state:'COMPLETE'},{key:'CODE',label:'代码分析',state:'COMPLETE'},{key:'CONTRIBUTORS',label:'人员贡献',state:'ACTIVE'},{key:'REPORT',label:'生成并校验报告',state:'PENDING'},{key:'COMPLETE',label:'完成',state:'PENDING'}]}}})
   if(path.endsWith('/sessions'))return route.fulfill({json:[{key:'execution:s',kind:'IMPLEMENTATION',label:'执行会话',localSessionId:'s',state:'COMPLETED',stageOrdinal:2,createdAt:'2026-09-13T00:00:00Z',templateBatch:{purpose:'CONTRIBUTOR',ordinal:2,total:4,overallOrdinal:12,overallTotal:14,repairRound:0,cleanup:false}}]})
   if(path.endsWith('/audit'))return route.fulfill({json:{attempts:[],errors:[],judges:[],artifacts:[]}})
   if(path.endsWith('/events'))return route.fulfill({contentType:'text/event-stream',body:''})
   return route.fulfill({json:[]})
  })
  await page.setViewportSize({width,height:1000});await page.goto('/databases')
  await expect(page.getByRole('heading',{name:'连接你的第一套数据库'})).toBeVisible()
  await page.screenshot({path:`test-results/database-empty-${width}.png`})
  await page.getByRole('button',{name:'新增数据库连接',exact:true}).click()
  await page.getByRole('textbox',{name:'连接名称',exact:true}).fill('内网业务库')
  await page.getByRole('textbox',{name:'主机',exact:true}).fill('db.internal')
  await page.getByRole('textbox',{name:'数据库名称',exact:true}).fill('app')
  await page.getByRole('textbox',{name:'只读账号',exact:true}).fill('reader')
  await page.getByRole('textbox',{name:'密码',exact:true}).fill('fixture-password')
  await page.getByRole('textbox',{name:'允许访问的数据库',exact:true}).fill('app')
  await page.getByRole('button',{name:'测试连接',exact:true}).click()
  await expect(page.getByText('连接成功 · 只读标记已确认')).toBeVisible();expect(tests).toBe(1);expect(saves).toBe(0)
  await page.screenshot({path:`test-results/database-drawer-${width}.png`})
  await page.getByRole('textbox',{name:'主机',exact:true}).fill('changed.internal');await expect(page.getByText('连接成功 · 只读标记已确认')).toHaveCount(0)
  await page.getByRole('button',{name:'取消',exact:true}).click()
  await page.goto('/tasks/fixture')
  await expect(page.getByRole('img',{name:'分析批次完成 12/14，85%'})).toBeVisible()
  await expect(page.getByRole('button',{name:/阶段 2 · 分析批次 12\/14/})).toBeVisible()
  await expect(page.getByText('人员贡献 · 第 2/4 批',{exact:false}).first()).toBeVisible()
  expect(await page.locator('body').evaluate(node=>node.scrollWidth<=window.innerWidth)).toBeTruthy()
  await page.screenshot({path:`test-results/template-progress-${width}.png`})
 })
}
