import { describe, expect, it } from 'vitest'
import { databaseBody, databaseDraft, changeDatabaseType, legacyDatabaseUrl } from './databaseDraft'
import type { DatabaseConnection, DatabaseTypeProfile } from '@/types/domain'
export const mysql: DatabaseTypeProfile = { id: 'mysql-built-in', type: 'MYSQL', label: 'MySQL', driverClass: 'driver', defaultPort: 3306, binaries: [{ filename: 'mysql.jar', sha256: 'known' }] }
export const connection: DatabaseConnection = { id: 'db-1', name: '财务库', config: { type: 'MYSQL', jdbcUrl: 'jdbc:mysql://old:3306/db?useSSL=true', host: 'old', port: 3306, database: 'db', username: 'reader', driverProfile: 'legacy', driverFile: 'custom.jar', driverClass: 'old.Driver', schemas: ['db'], parameters: {}, timeoutSeconds: 30, maxRows: 500 }, passwordConfigured: true, enabled: true, archived: false, projectIds: ['project-1'], version: 5, createdAt: '2026-10-01' }
describe('React database input preserves the existing DTO contract', () => {
  it('preserves a historical JDBC URL, version and account while upgrading the driver', () => {
    const draft = databaseDraft(connection)
    expect(draft.config.jdbcUrl).toBe(connection.config.jdbcUrl); expect(draft.version).toBe(5)
    expect(draft.config.driverProfile).toBeFalsy(); expect(draft.config.driverFile).toBe(''); expect(draft.config.driverClass).toBe('')
    const body = databaseBody(draft, '', 'db', [mysql], true)
    expect(body.password).toBeNull(); expect(body.config.jdbcUrl).toBe(connection.config.jdbcUrl)
    expect(connection.config.driverProfile).toBe('legacy')
  })
  it('retains password whitespace and rejects a missing new password', () => {
    const draft = databaseDraft(null); draft.name = '新库'; draft.config.username = 'reader'; draft.config.jdbcUrl = 'jdbc:mysql://localhost/db'
    expect(() => databaseBody(draft, '', 'db', [mysql], false)).toThrow()
    expect(databaseBody(draft, ' pass word ', 'db', [mysql], false).password).toBe(' pass word ')
  })
  it('clears URL and vendor parameters on a type change', () => {
    const draft = databaseDraft(connection); draft.config.parameters = { encrypt: 'true' }
    const changed = changeDatabaseType(draft, 'SQLSERVER')
    expect(changed.config.jdbcUrl).toBe(''); expect(changed.config.parameters).toEqual({}); expect(changed.config.type).toBe('SQLSERVER')
  })
  it('keeps legacy IPv6 and SQL Server parameters in generated URLs', () => {
    expect(legacyDatabaseUrl({ ...connection.config, jdbcUrl: undefined, host: '::1', parameters: { x: 'a b' } })).toContain('[::1]')
    expect(legacyDatabaseUrl({ ...connection.config, type: 'SQLSERVER', jdbcUrl: undefined, parameters: { encrypt: 'false' } })).toContain(';encrypt=false')
  })
})
