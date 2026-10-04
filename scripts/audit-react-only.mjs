/** Fail closed for executable framework residue. Historical prose is not executable code. */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
const project=resolve(dirname(fileURLToPath(import.meta.url)),'..'),frontend=resolve(project,'frontend')
const require=createRequire(resolve(frontend,'package.json')),ts=require('typescript')
export const forbiddenPackage = value => /^(?:vue(?:$|[-/])|pinia(?:$|\/)|element-plus(?:$|\/)|@vue\/|@vueuse\/|@iconify\/vue(?:$|\/)|@vitejs\/plugin-vue(?:$|\/)|vue-tsc(?:$|\/))/.test(value)
export function moduleReferences(text,file='input.ts'){
 const ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,file.endsWith('x')?ts.ScriptKind.TSX:ts.ScriptKind.TS),found=[]
 function record(node){if(node&&ts.isStringLiteralLike(node))found.push({value:node.text,line:ast.getLineAndCharacterOfPosition(node.getStart(ast)).line+1})}
 function visit(node){
  if(ts.isImportDeclaration(node)||ts.isExportDeclaration(node))record(node.moduleSpecifier)
  else if(ts.isCallExpression(node)&&(node.expression.kind===ts.SyntaxKind.ImportKeyword||ts.isIdentifier(node.expression)&&node.expression.text==='require'))record(node.arguments[0])
  else if(ts.isImportEqualsDeclaration(node)&&ts.isExternalModuleReference(node.moduleReference))record(node.moduleReference.expression)
  else if(ts.isLiteralTypeNode(node)&&ts.isStringLiteral(node.literal)&&node.parent?.kind===ts.SyntaxKind.ImportType)record(node.literal)
  ts.forEachChild(node,visit)
 }
 visit(ast);return found
}
export function auditSources(root){
 const errors=[],counts={files:0,imports:0}
 function visit(directory){if(!existsSync(directory))return;for(const entry of readdirSync(directory,{withFileTypes:true})){
  if(['node_modules','dist','.git','test-results','playwright-report'].includes(entry.name))continue
  const path=resolve(directory,entry.name),name=relative(root,path).replaceAll('\\','/')
  if(entry.isDirectory()){visit(path);continue}
  if(name.endsWith('.vue')){errors.push(`${name}: executable SFC remains`);continue}
  if(/\.(?:[cm]?[jt]sx?|html|json|css)$/.test(name)){
   counts.files++;const content=readFileSync(path,'utf8')
   if(/\.(?:[cm]?[jt]sx?)$/.test(name))for(const ref of moduleReferences(content,name)){counts.imports++;if(forbiddenPackage(ref.value)||/\.vue(?:$|\?)/.test(ref.value))errors.push(`${name}:${ref.line}: forbidden module ${ref.value}`)}
   if(name.endsWith('.json')){let data;try{data=JSON.parse(content)}catch{continue};for(const group of ['dependencies','devDependencies','peerDependencies','optionalDependencies'])for(const key of Object.keys(data[group]??{}))if(forbiddenPackage(key))errors.push(`${name}: ${group}.${key}`);if(data.packages)for(const key of Object.keys(data.packages))if(key.includes('node_modules/')&&forbiddenPackage(key.split('node_modules/').at(-1)))errors.push(`${name}: locked ${key}`)}
   if(name.endsWith('.html'))for(const [,value] of content.matchAll(/(?:src|href)=["']([^"']+)["']/g))if(/\.vue(?:$|\?)/.test(value))errors.push(`${name}: executable SFC entry ${value}`)
  }
 }}
 visit(root);return {errors,counts}
}
export function auditDependencyTree(tree){const errors=[],names=new Set();function walk(row){for(const [name,child] of Object.entries(row.dependencies??{})){names.add(name);if(forbiddenPackage(name))errors.push(`${name}@${child.version??'unknown'}`);walk(child)}}walk(tree);return {errors,packages:names.size}}
export function auditBuild(manifest){const errors=[];if(!Array.isArray(manifest.modules)||!manifest.modules.length)errors.push('Build module inventory missing/empty');for(const id of manifest.modules??[]){const name=id.split('node_modules/').at(-1);if(forbiddenPackage(name)||/\.vue(?:$|\?)/.test(id))errors.push(`Build module ${id}`)}return {errors,modules:manifest.modules?.length??0}}
export function run(){
 const source=auditSources(frontend),scripts=auditSources(resolve(project,'scripts'))
 const tree=JSON.parse(execFileSync('npm',['ls','--all','--json'],{cwd:frontend,encoding:'utf8',maxBuffer:16*1024*1024}))
 const dependencies=auditDependencyTree(tree),manifestPath=resolve(frontend,'dist/react-module-inventory.json')
 if(!existsSync(manifestPath))throw new Error('Production build inventory missing: run npm run build first')
 const build=auditBuild(JSON.parse(readFileSync(manifestPath,'utf8')))
 const errors=[...source.errors,...scripts.errors,...dependencies.errors,...build.errors]
 const result={ok:errors.length===0,sourceFiles:source.counts.files,sourceModuleReferences:source.counts.imports,scriptFiles:scripts.counts.files,dependencyPackages:dependencies.packages,buildModules:build.modules,errors}
 console.log(JSON.stringify(result,null,2));if(errors.length)process.exitCode=1;return result
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))run()
