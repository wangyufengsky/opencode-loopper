import { lstat, realpath, readdir } from 'node:fs/promises'
import path from 'node:path'

// Native tools stay native. Only the project read boundary is shared with knowledge MCP.
const native = new Set(['read', 'glob', 'grep'])
const excluded = new Set(['node_modules', 'target', 'dist', 'build', 'vendor', 'coverage', '__pycache__', 'credentials', 'secrets'])
const allowed = relative => relative.split(/[\\/]/).every(part => {
  const name = part.toLowerCase()
  return !name.startsWith('.') && !excluded.has(name) && !/\.(key|pem|p12|jks)$/.test(name)
    && !/^(credentials|secrets?)[._-]/.test(name) && !/^id_(rsa|ed25519|dsa|ecdsa)(\.pub)?$/.test(name)
})
const denied = () => new Error('LOOPPER_RESEARCH_PATH_DENIED: 只能读取当前项目内的非敏感资料；请选择项目内的真实路径继续调查。')
const fingerprint = stat => `${stat.dev}:${stat.ino}:${stat.size}:${stat.mtimeMs}`
const escapeGlob = text => text.replace(/[\\{}[\]*?!]/g, '\\$&')
const includePattern = pattern => {
  if (!pattern) return () => true
  let source = '', braces = 0
  for (let i = 0; i < pattern.length; i++) {
    const char = pattern[i]
    if (char === '*' && pattern[i + 1] === '*') { source += pattern[i + 2] === '/' ? '(?:.*/)?' : '.*'; i += pattern[i + 2] === '/' ? 2 : 1 }
    else if (char === '*') source += '[^/]*'
    else if (char === '?') source += '[^/]'
    else if (char === '{') { source += '(?:'; braces++ }
    else if (char === '}' && braces > 0) { source += ')'; braces-- }
    else if (char === ',' && braces > 0) source += '|'
    else if (char === '[') {
      const end = pattern.indexOf(']', i + 1)
      if (end < 0) throw new Error('include 中的字符集合不完整，请调整文件匹配条件。')
      source += pattern.slice(i, end + 1); i = end
    } else source += char.replace(/[.()+^$|\\]/g, '\\$&')
  }
  if (braces) throw new Error('include 中的大括号不完整，请调整文件匹配条件。')
  const regex = new RegExp('^' + (pattern.includes('/') ? '' : '(?:.*/)?') + source.replace(/^\//, '') + '$')
  return value => regex.test(value)
}
const validate = async (root, value) => {
  const selected = path.resolve(root, value || '.')
  const relative = path.relative(root, selected)
  if (path.isAbsolute(relative) || relative === '..' || relative.startsWith(`..${path.sep}`) || relative && !allowed(relative)) throw denied()
  let current = root
  if (await realpath(root) !== root) throw denied()
  for (const part of relative.split(path.sep).filter(Boolean)) {
    current = path.join(current, part)
    if ((await lstat(current)).isSymbolicLink()) throw denied()
  }
  const stat = await lstat(selected)
  if (!stat.isFile() && !stat.isDirectory() || await realpath(selected) !== selected) throw denied()
  return { selected, stat }
}

// Ripgrep receives an explicit safe file set, so native content search cannot traverse secret or linked paths.
const searchFiles = async (root, start, include) => {
  const pending = [start], files = []
  const deadline = Date.now() + 3000
  let bytes = 0, visited = 0, limited = false
  const matches = includePattern(include)
  while (pending.length && !limited) {
    const directory = pending.pop()
    const entries = await readdir(directory, { withFileTypes: true })
    entries.sort((a, b) => a.name.localeCompare(b.name))
    for (const entry of entries) {
      if (++visited > 12000 || Date.now() > deadline || bytes > 60000) { limited = true; break }
      const file = path.join(directory, entry.name)
      if (entry.isSymbolicLink() || !allowed(path.relative(root, file))) continue
      if (entry.isDirectory()) pending.push(file)
      else if (entry.isFile()) {
        const relative = path.relative(start, file).split(path.sep).join('/')
        if (!matches(relative)) continue
        const pattern = escapeGlob(relative)
        files.push(pattern); bytes += Buffer.byteLength(pattern) + 1
      }
    }
  }
  return { files, limited }
}

export const LoopperKnowledgeGuard = async ({ client, directory }) => {
  const sessions = new Map(), calls = new Map()
  const scope = async sessionID => {
    if (sessions.has(sessionID)) return sessions.get(sessionID)
    const response = await client.session.get({ path: { id: sessionID }, query: { directory } })
    if (!response.data) throw new Error('LOOPPER_RESEARCH_SESSION_UNKNOWN: 无法核对文件读取会话，请重试。')
    const research = response.data.permission?.some(rule => rule.permission === 'loopper_knowledge_research' && rule.action === 'allow')
    const root = research ? await realpath(response.data.directory || directory) : null
    sessions.set(sessionID, root)
    return root
  }
  const safe = async (root, value) => { try { await validate(root, value); return true } catch { return false } }
  return {
    'tool.execute.before': async (input, output) => {
      if (!native.has(input.tool)) return
      const root = await scope(input.sessionID)
      if (!root) return
      const checked = await validate(root, input.tool === 'read' ? output.args.filePath : output.args.path)
      const state = { root, ...checked, fingerprint: fingerprint(checked.stat), limited: false, include: output.args.include }
      if (input.tool === 'read') {
        if (checked.stat.isFile() && /\.(pdf|docx|xlsx|pptx)$/i.test(checked.selected))
          throw new Error('LOOPPER_RESEARCH_DOCUMENT: 此文件请按需使用知识库文档工具解析，其他项目文件仍可原生读取。')
        output.args.filePath = checked.selected
      } else {
        if (!checked.stat.isDirectory()) throw new Error('LOOPPER_RESEARCH_DIRECTORY: 搜索 path 应为目录；请用 include 指定文件名。')
        output.args.path = checked.selected
        if (input.tool === 'grep') {
          const selection = await searchFiles(root, checked.selected, output.args.include)
          // A positive root-anchored glob is intersected with native ripgrep's file enumeration.
          output.args.include = selection.files.length === 1 ? selection.files[0] : selection.files.length ? `{${selection.files.join(',')}}` : '__loopper_no_readable_files__'
          state.limited = selection.limited
        }
      }
      calls.set(`${input.sessionID}:${input.callID}`, state)
      if (calls.size > 128) calls.delete(calls.keys().next().value)
    },
    'tool.execute.after': async (input, output) => {
      if (!native.has(input.tool)) return
      const root = await scope(input.sessionID)
      if (!root) return
      const key = `${input.sessionID}:${input.callID}`, state = calls.get(key)
      calls.delete(key)
      if (!state) throw new Error('LOOPPER_RESEARCH_CALL_UNKNOWN: 请重新读取以核对当前文件身份。')
      if (input.tool === 'grep') {
        if (state.include === undefined) delete input.args.include
        else input.args.include = state.include
      }
      const current = await validate(root, state.selected)
      if (input.tool === 'read') {
        if (fingerprint(current.stat) !== state.fingerprint) throw new Error('LOOPPER_RESEARCH_CHANGED: 读取期间文件发生变化，请重新读取。')
        if (current.stat.isDirectory()) {
          const entries = []
          for (const name of await readdir(state.selected)) if (await safe(root, path.join(state.selected, name))) entries.push(name)
          entries.sort()
          const offset = Math.max(0, (input.args.offset || 1) - 1), limit = Math.min(input.args.limit || 2000, 2000)
          output.output = `目录 ${state.selected}\n${entries.slice(offset, offset + limit).join('\n')}\n`
            + (offset + limit < entries.length ? `目录未读完，使用 offset=${offset + limit + 1} 继续。` : '目录已列出。')
        } else {
          // Native read may append project instructions. They are not part of the read evidence.
          output.output = output.output.replace(/(<\/(?:file|content)>)[\s\S]*$/, '$1')
        }
      } else if (input.tool === 'glob') {
        const files = []
        for (const line of output.output.split('\n')) if (path.isAbsolute(line) && await safe(root, line)) files.push(line)
        output.output = files.length ? files.join('\n') : '本次未找到可读取的项目文件；可调整路径或关键词继续调查。'
      } else {
        const lines = []
        let accept = false
        for (const line of output.output.split('\n')) {
          if (path.isAbsolute(line) && line.endsWith(':')) {
            const file = line.slice(0, -1)
            accept = await safe(root, file)
            if (accept) lines.push(line)
          } else if (accept && /^\s+Line \d+:/.test(line)) lines.push(line)
        }
        output.output = lines.length ? lines.join('\n') : '本次未命中可读取的项目内容；无命中不证明不存在，可调整目录或关键词继续调查。'
      }
      const truncated = Boolean(output.metadata?.truncated) || state.limited
      if (truncated) output.output += '\n本次结果未完整覆盖；请缩小目录、调整检索条件或继续读取相关文件，查清后再回答。'
      output.metadata = { truncated, preview: output.output.slice(0, 2000) }
      output.attachments = []
    },
  }
}
