import type { PptJob } from '@/types/domain'

export function slidePreviews(
  jobs: readonly PptJob[],
  revision: number,
  artifactUrl: (id: string) => string,
): ReadonlyMap<string, string> {
  const result = new Map<string, string>()
  for (const job of jobs) {
    if (job.kind !== 'PREVIEW' || job.revision !== revision) continue
    for (const artifact of job.artifacts) {
      if (artifact.slideId && artifact.mediaType === 'image/png' && !result.has(artifact.slideId))
        result.set(artifact.slideId, artifactUrl(artifact.id))
    }
  }
  return result
}
