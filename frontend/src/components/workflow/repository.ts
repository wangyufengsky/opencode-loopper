export function repositoryBranchLabel(value: string) {
  if (value.startsWith('local:refs/heads/')) return `本地 · ${value.slice(17)}`
  const remote = /^remote:([^:]+):refs\/heads\/(.+)$/.exec(value)
  return remote ? `远程 ${remote[1]} · ${remote[2]}` : value ? '所选分支无法识别，请重新选择' : '尚未选择分支'
}
