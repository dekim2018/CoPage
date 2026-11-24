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

/** 대기 중인 초대. 토큰은 만들 때만 받는다(CreatedInvite). */
export interface PendingInvite {
  id: string
  email: string
  role: WorkspaceRole
  createdAt: string
  expiresAt: string
}

export interface CreatedInvite {
  id: string
  email: string
  role: WorkspaceRole
  expiresAt: string
  token: string
}

/** 초대 링크를 연 사람에게 보일 내용. */
export interface InvitePreview {
  workspaceId: string
  workspaceName: string
  email: string
  role: WorkspaceRole
  expiresAt: string
}

/** 공유 링크 설정. token 은 새로 만들었을 때만 있다(D-41). */
export interface ShareLinkSetting {
  level: PageRole
  expiresAt: string | null
  createdAt: string
  token: string | null
}

/** 링크로 연 페이지. */
export interface SharedPage {
  pageId: string
  title: string
  level: PageRole
}

/** 휴지통의 페이지. canRestore 는 휴지통에 가기 전 FULL 이었는지다. */
export interface TrashedPage {
  id: string
  parentId: string | null
  title: string
  archivedAt: string
  canRestore: boolean
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
  movePage: (pageId: string, parentId: string | null, afterId: string | null) =>
    api<PageDetail>(`/api/pages/${pageId}/move`, { method: 'POST', ...json({ parentId, afterId }) }),
  trashPage: (pageId: string) => api<void>(`/api/pages/${pageId}`, { method: 'DELETE' }),
  restorePage: (pageId: string) => api<PageDetail>(`/api/pages/${pageId}/restore`, { method: 'POST' }),
  trash: (workspaceId: string) => api<TrashedPage[]>(`/api/workspaces/${workspaceId}/trash`),
  shareLink: (pageId: string) => api<ShareLinkSetting | undefined>(`/api/pages/${pageId}/share-link`),
  saveShareLink: (pageId: string, level: PageRole, expiresAt: string | null, regenerate: boolean) =>
    api<ShareLinkSetting>(`/api/pages/${pageId}/share-link`, {
      method: 'PUT',
      ...json({ level, expiresAt, regenerate }),
    }),
  disableShareLink: (pageId: string) => api<void>(`/api/pages/${pageId}/share-link`, { method: 'DELETE' }),
  openShared: (token: string) => api<SharedPage>(`/api/share/${encodeURIComponent(token)}`),
  invites: (workspaceId: string) => api<PendingInvite[]>(`/api/workspaces/${workspaceId}/invites`),
  invite: (workspaceId: string, email: string, role: WorkspaceRole) =>
    api<CreatedInvite>(`/api/workspaces/${workspaceId}/invites`, { method: 'POST', ...json({ email, role }) }),
  revokeInvite: (workspaceId: string, inviteId: string) =>
    api<void>(`/api/workspaces/${workspaceId}/invites/${inviteId}`, { method: 'DELETE' }),
  previewInvite: (token: string) => api<InvitePreview>(`/api/invites/${encodeURIComponent(token)}`),
  acceptInvite: (token: string) =>
    api<{ workspaceId: string }>(`/api/invites/${encodeURIComponent(token)}/accept`, { method: 'POST' }),
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
