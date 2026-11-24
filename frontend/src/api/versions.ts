import { api } from './http'

/** 버전 기록 (docs/09-API.md 11.4, D-44). */
export interface VersionInfo {
  id: string
  rev: number
  kind: 'AUTO' | 'NAMED' | 'RESTORE'
  name: string | null
  createdAt: string
  editors: string[]
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) })

export const versionsApi = {
  list: (docId: string, before?: number) =>
    api<VersionInfo[]>(`/api/docs/${docId}/versions` + (before === undefined ? '' : `?before=${before}`)),
  at: (docId: string, rev: number) => api<{ rev: number; document: unknown }>(`/api/docs/${docId}/versions/${rev}`),
  record: (docId: string, kind: 'NAMED' | 'RESTORE', name?: string, restoredFrom?: number) =>
    api<VersionInfo>(`/api/docs/${docId}/versions`, { method: 'POST', ...json({ kind, name, restoredFrom }) }),
}
