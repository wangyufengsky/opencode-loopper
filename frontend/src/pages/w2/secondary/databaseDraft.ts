import type { DatabaseConfig, DatabaseConnection, DatabaseConnectionInput, DatabaseTypeProfile } from '@/types/domain'

export const databaseTypeLabels: Record<DatabaseConfig['type'], string> = {
  MYSQL: 'MySQL', OPENGAUSS: 'openGauss', GAUSSDB: 'GaussDB', GOLDENDB: 'GoldenDB（历史配置）',
  DAMENG: '达梦', ORACLE: 'Oracle', DB2: 'DB2', SQLSERVER: 'SQL Server',
}
export const databaseUrlExamples: Record<DatabaseConfig['type'], string> = {
  MYSQL: 'jdbc:mysql://host:3306/database', OPENGAUSS: 'jdbc:postgresql://host1:8000,host2:8000/database?targetServerType=master',
  GAUSSDB: 'jdbc:postgresql://host1:8000,host2:8000/database?targetServerType=master', ORACLE: 'jdbc:oracle:thin:@//host:1521/service',
  DB2: 'jdbc:db2://host:50000/database', SQLSERVER: 'jdbc:sqlserver://host:1433;databaseName=app;encrypt=true;trustServerCertificate=false',
  DAMENG: 'jdbc:dm://host:5236', GOLDENDB: 'jdbc:goldendb://host:3306/database',
}
export function legacyDatabaseUrl(config: DatabaseConfig): string {
  const host = config.host.includes(':') ? `[${config.host}]` : config.host
  const prefixes = { MYSQL: 'mysql', GAUSSDB: 'postgresql', OPENGAUSS: 'postgresql', ORACLE: 'oracle:thin:@', DB2: 'db2', SQLSERVER: 'sqlserver', DAMENG: 'dm', GOLDENDB: 'goldendb' }
  const query = config.type === 'DB2'
    ? Object.entries(config.parameters).map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)};`).join('')
    : new URLSearchParams(config.parameters).toString()
  if (config.type === 'SQLSERVER') return `jdbc:sqlserver://${host}:${config.port};databaseName=${config.database}${Object.entries(config.parameters).map(([key, value]) => `;${key}=${value}`).join('')}`
  if (config.type === 'ORACLE') return `jdbc:oracle:thin:@//${host}:${config.port}/${config.database}${query ? `?${query}` : ''}`
  return `jdbc:${prefixes[config.type]}://${host}:${config.port}${config.type === 'DAMENG' ? '' : `/${config.database}`}${query ? `${config.type === 'DB2' ? ':' : '?'}${query}` : ''}`
}
export function databaseDraft(row: DatabaseConnection | null): DatabaseConnectionInput {
  const form: DatabaseConnectionInput = row
    ? { name: row.name, config: { ...row.config, schemas: [...row.config.schemas], parameters: { ...row.config.parameters } }, enabled: row.enabled, archived: row.archived, projectIds: [...row.projectIds], version: row.version, password: null }
    : { name: '', password: null, enabled: true, archived: false, projectIds: [], version: 0,
      config: { type: 'MYSQL', jdbcUrl: '', host: '', port: 3306, database: '', username: '', driverFile: '', driverClass: '', schemas: [], parameters: {}, timeoutSeconds: 10, maxRows: 200 } }
  form.config.driverFile = ''; form.config.driverClass = ''; form.config.driverProfile = null
  form.config.jdbcUrl ||= legacyDatabaseUrl(form.config)
  if (!row) form.config.jdbcUrl = ''
  return form
}
export function changeDatabaseType(form: DatabaseConnectionInput, type: DatabaseConfig['type']): DatabaseConnectionInput {
  return { ...form, config: { ...form.config, type, driverFile: '', driverClass: '', driverProfile: null, jdbcUrl: '', parameters: {} } }
}
export function databaseBody(form: DatabaseConnectionInput, password: string, schemas: string, types: readonly DatabaseTypeProfile[], existing: boolean): DatabaseConnectionInput {
  if (!types.some(profile => profile.type === form.config.type) || !form.name.trim() || !form.config.jdbcUrl?.trim() || !form.config.username.trim()) throw new Error('请填写名称、JDBC URL 和只读账号')
  const allowed = schemas.split(/[,，\n]/).map(value => value.trim()).filter(Boolean)
  if (!allowed.length) throw new Error('请填写允许访问的数据库或 schema')
  if (!existing && !password) throw new Error('请填写数据库密码')
  if (!Number.isInteger(form.config.timeoutSeconds) || form.config.timeoutSeconds < 1 || form.config.timeoutSeconds > 30
    || !Number.isInteger(form.config.maxRows) || form.config.maxRows < 1 || form.config.maxRows > 1000) throw new Error('查询超时须为 1–30 秒，返回行数须为 1–1000 行')
  return { ...form, projectIds: [...form.projectIds], config: { ...form.config, schemas: allowed, parameters: {} }, password: password || null }
}
