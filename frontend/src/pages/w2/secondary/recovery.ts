import type { DatabaseConnection, DatabaseConnectionInput, McpPolicyCatalog } from '@/types/domain'
import type { OriginalLookup } from '@/foundation/contracts/receipt'

/** CAS + desired state can confirm an existing public configuration. Secrets cannot be read back. */
export function confirmsDatabase(row: DatabaseConnection | undefined, body: DatabaseConnectionInput) {
  if (!row || body.password !== null || row.version !== body.version + 1) return false
  const expected = body.config, actual = row.config
  return row.name === body.name && row.enabled === body.enabled && row.archived === body.archived
    && sameSet(row.projectIds, body.projectIds) && actual.type === expected.type
    && actual.jdbcUrl === expected.jdbcUrl && actual.username === expected.username
    && sameSet(actual.schemas, expected.schemas) && actual.timeoutSeconds === expected.timeoutSeconds
    && actual.maxRows === expected.maxRows && JSON.stringify(actual.parameters) === JSON.stringify(expected.parameters)
}
function sameSet(a: readonly string[], b: readonly string[]) { return a.length === b.length && [...a].sort().every((value, index) => value === [...b].sort()[index]) }
export function confirmsPolicy(catalog: McpPolicyCatalog, project: string, requests: readonly { name: string; version: number; enabled: number }[]): OriginalLookup<void> {
  const confirmed = catalog.complete && requests.every(request => {
    const row = catalog.tools.find(tool => tool.name === request.name)
    if (!row) return false
    // A missing project override is inserted at version 0; existing rows increment exactly once.
    const version = project ? row.projectVersion : row.globalVersion
    return version === (request.version < 0 ? 0 : request.version + 1)
      && (project ? row.projectOverride === (request.enabled < 0 ? 'INHERIT' : request.enabled ? 'ENABLED' : 'DISABLED') : row.globalEnabled === !!request.enabled)
  })
  return confirmed ? { kind: 'ACCEPTED', receipt: undefined } : { kind: 'UNCONFIRMED' }
}
