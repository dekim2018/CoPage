/* 워크스페이스, 페이지 트리, 공유 API (docs/09-API.md 11.2, 11.3). */
import { api } from './http'
import type { PageRole } from '../collab/client/role'

export type WorkspaceRole = 'OWNER' | 'MEMBER' | 'GUEST'

export interface Workspace {
  id: string
  name: string
  role: WorkspaceRole
}

export interface PageNode {
  id: string
  workspaceId: string
  parentId: string | null
  title: string
  /** 보관되지 않은 하위 페이지가 있는지. 권한을 보지 않고 센다(D-12). 펼치면 빈 목록일 수 있다 */
  hasChildren: boolean
  level: PageRole
}

export interface PageDetail {
  id: string
  workspaceId: string
  parentId: string | null
  title: string
  /** 상속을 끊었는지 */
  restricted: boolean
  level: PageRole
  /** 볼 수 있는 조상. 루트 쪽부터 */
  path: { id: string; title: string }[]
}

export interface Member {
  userId: string
  email: string
  name: string
  role: WorkspaceRole
}

export interface Grant {
  userId: string
  email: string
  name: string
  level: PageRole
  /** 조상 페이지에서 물려받은 것이면 그 페이지 ID */
  inheritedFrom: string | null
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) })

export const workspaceApi = {
  list: () => api<Workspace[]>('/api/workspaces'),
  create: (name: string) => api<Workspace>('/api/workspaces', { method: 'POST', ...json({ name }) }),
  members: (workspaceId: string) => api<Member[]>(`/api/workspaces/${workspaceId}/members`),
  addMember: (workspaceId: string, email: string, role: WorkspaceRole) =>
    api<Member>(`/api/workspaces/${workspaceId}/members`, { method: 'POST', ...json({ email, role }) }),
  changeRole: (workspaceId: string, userId: string, role: WorkspaceRole) =>
    api<void>(`/api/workspaces/${workspaceId}/members/${userId}`, { method: 'PATCH', ...json({ role }) }),
  removeMember: (workspaceId: string, userId: string) =>
    api<void>(`/api/workspaces/${workspaceId}/members/${userId}`, { method: 'DELETE' }),
  children: (workspaceId: string, parentId: string | null) =>
    api<PageNode[]>(
      `/api/workspaces/${workspaceId}/pages` + (parentId === null ? '' : `?parentId=${encodeURIComponent(parentId)}`),
    ),
  createPage: (workspaceId: string, parentId: string | null) =>
    api<PageNode>(`/api/workspaces/${workspaceId}/pages`, { method: 'POST', ...json({ parentId }) }),
  page: (pageId: string) => api<PageDetail>(`/api/pages/${pageId}`),
  grants: (pageId: string) => api<Grant[]>(`/api/pages/${pageId}/grants`),
  grantByEmail: (pageId: string, email: string, level: PageRole) =>
    api<Grant>(`/api/pages/${pageId}/grants`, { method: 'POST', ...json({ email, level }) }),
  setGrant: (pageId: string, userId: string, level: PageRole) =>
    api<Grant>(`/api/pages/${pageId}/grants/${userId}`, { method: 'PUT', ...json({ level }) }),
  revoke: (pageId: string, userId: string) =>
    api<void>(`/api/pages/${pageId}/grants/${userId}`, { method: 'DELETE' }),
  setRestricted: (pageId: string, restricted: boolean) =>
    api<{ restricted: boolean }>(`/api/pages/${pageId}/restricted`, { method: 'PUT', ...json({ restricted }) }),
}
