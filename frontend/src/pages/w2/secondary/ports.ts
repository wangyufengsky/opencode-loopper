export interface PptCreationFile {
  id: string; name: string; size: number; modified: number; kind: 'sources' | 'assets'
  file?: File; uploaded: boolean; key: string
}
