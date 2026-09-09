export function normalizeCommitSubject(value: string) {
  return value.replace(/[\s\p{Z}]+/gu, ' ').trim()
}

export function suggestedCommitSubject(value: string, limit = 120) {
  return Array.from(normalizeCommitSubject(value.replace(/^#[0-9]{4}_/, ''))).slice(0, limit).join('')
}

export function validCommitSubject(value: string) {
  const subject = normalizeCommitSubject(value)
  return subject.length > 0 && Array.from(subject).length <= 120 && !/[\u0000-\u001f\u007f]/u.test(subject)
}
