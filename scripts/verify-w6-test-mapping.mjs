/** Account for every original W5 assertion after executable Vue retirement. */
import {readFileSync,writeFileSync} from 'node:fs'
import {resolve,dirname} from 'node:path'
import {fileURLToPath} from 'node:url'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..')
const [resultPath,outputPath]=process.argv.slice(2)
if(!resultPath)throw new Error('Usage: node scripts/verify-w6-test-mapping.mjs <vitest-json> [output-json]')
const baseline=JSON.parse(readFileSync(resolve(root,'docs/design/react-full-migration/evidence/w6/baseline.json'),'utf8'))
const final=JSON.parse(readFileSync(resolve(resultPath),'utf8'))
const cases=final.testResults.flatMap(suite=>suite.assertionResults.map(test=>({...test,path:suite.name.slice(suite.name.indexOf('/frontend/')+1)})))
const actual=new Map(),original=new Map()
for(const test of cases){const values=actual.get(test.fullName)??[];values.push(test);actual.set(test.fullName,values)}
for(const suite of baseline.tests)for(const test of suite.assertions)original.set(test.fullName,(original.get(test.fullName)??0)+1)
const missing=[],failed=cases.filter(test=>test.status!=='passed').map(test=>({path:test.path,fullName:test.fullName,status:test.status}))
// Collection failures can have no assertions; never infer a green run from their empty list.
const suiteFailures=final.testResults.filter(suite=>suite.status!=='passed').map(suite=>({path:suite.name.slice(suite.name.indexOf('/frontend/')+1),status:suite.status,message:suite.message??''}))
const runPassed=final.success===true && (final.numRuntimeErrorTestSuites??0)===0 && final.numFailedTests===0 && final.numPendingTests===0 && final.numTotalTests===cases.length && suiteFailures.length===0
for(const [name,count] of original)if((actual.get(name)?.length??0)<count)missing.push({fullName:name,original:count,actual:actual.get(name)?.length??0})
const files=baseline.tests.map(suite=>({original:suite.path,count:suite.total,vueExecutable:suite.vueExecutable,
 destinations:[...new Set(suite.assertions.flatMap(test=>(actual.get(test.fullName)??[]).map(value=>value.path)))],
 retained:suite.assertions.every(test=>actual.has(test.fullName))}))
const added=[]
for(const [name,tests] of actual){const delta=tests.length-(original.get(name)??0);if(delta>0)added.push({fullName:name,count:delta,paths:[...new Set(tests.map(test=>test.path))]})}
const result={ok:runPassed&&missing.length===0&&failed.length===0,runPassed,suiteFailures,baseline:baseline.baseline,originalTests:baseline.totalTests,originalFiles:baseline.tests.length,
 finalTests:cases.length,finalFiles:final.testResults.length,newTests:added.reduce((sum,value)=>sum+value.count,0),missing,failed,added,files}
if(outputPath)writeFileSync(resolve(outputPath),JSON.stringify(result,null,2)+'\n')
console.log(JSON.stringify({ok:result.ok,originalTests:result.originalTests,finalTests:result.finalTests,newTests:result.newTests,missing:missing.length,failed:failed.length},null,2))
if(!result.ok)process.exitCode=1
