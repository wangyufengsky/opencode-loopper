import { realpathSync } from 'node:fs'
import { isAbsolute, relative, resolve, sep } from 'node:path'

/** The lead provides a real Spring endpoint and a dedicated project root. No default server. */
export function integrationEnvironment() {
  const baseURL = process.env.BACKEND_INTEGRATION_BASE_URL
  const allowedRoot = process.env.BACKEND_INTEGRATION_ALLOWED_ROOT
  const projectRoot = process.env.BACKEND_INTEGRATION_PROJECT_ROOT
  const evidenceDir = process.env.BACKEND_INTEGRATION_EVIDENCE_DIR
  if (!baseURL || !allowedRoot || !projectRoot || !evidenceDir) throw new Error('Required: BACKEND_INTEGRATION_BASE_URL, BACKEND_INTEGRATION_ALLOWED_ROOT, BACKEND_INTEGRATION_PROJECT_ROOT, BACKEND_INTEGRATION_EVIDENCE_DIR')
  const endpoint = new URL(baseURL)
  if (!['http:', 'https:'].includes(endpoint.protocol) || !['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname) || endpoint.username || endpoint.password || endpoint.pathname !== '/' || endpoint.search || endpoint.hash) throw new Error('The integration endpoint must be the lead-owned local origin without credentials or a path')
  if (![allowedRoot, projectRoot, evidenceDir].every(isAbsolute)) throw new Error('Integration paths must be absolute')
  const allowed = realpathSync(allowedRoot), project = realpathSync(projectRoot)
  const nested = relative(allowed, project)
  if (!nested || nested === '..' || nested.startsWith(`..${sep}`) || isAbsolute(nested)) throw new Error('The browser project root must be a dedicated child of allowedRoot')
  return { baseURL: endpoint.origin, allowedRoot: allowed, projectRoot: project, evidenceDir: resolve(evidenceDir) }
}

export function localOrigin(value: string | undefined, name: string): string {
  if (!value) throw new Error(`${name} must name an explicit local origin`)
  const url = new URL(value)
  if (!['http:', 'https:'].includes(url.protocol) || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error(`${name} must be loopback, without credentials/path/query/hash`)
  return url.origin
}
