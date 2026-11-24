import { api } from './http'

/** 댓글 (docs/09-API.md, C-1~C-4, D-46). */
export interface CommentItem {
  id: string
  authorId: string
  authorName: string
  body: string
  createdAt: string
}

export interface CommentThread {
  id: string
  quote: string
  createdAt: string
  resolved: boolean
  resolvedByName: string | null
  comments: CommentItem[]
}

export interface NotificationItem {
  id: string
  kind: 'MENTION' | 'COMMENT_MENTION' | 'REPLY'
  docId: string | null
  actorName: string | null
  preview: string
  createdAt: string
  read: boolean
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) })

export const commentsApi = {
  threads: (docId: string) => api<CommentThread[]>(`/api/docs/${docId}/comments`),
  create: (docId: string, quote: string, body: string, mentions: string[]) =>
    api<CommentThread>(`/api/docs/${docId}/comments`, { method: 'POST', ...json({ quote, body, mentions }) }),
  reply: (threadId: string, body: string, mentions: string[]) =>
    api<CommentItem>(`/api/comments/${threadId}/replies`, { method: 'POST', ...json({ body, mentions }) }),
  resolve: (threadId: string, resolved: boolean) =>
    api<void>(`/api/comments/${threadId}/resolved`, { method: 'PUT', ...json({ resolved }) }),
}

export const notificationsApi = {
  inbox: () => api<{ unread: number; items: NotificationItem[] }>('/api/notifications'),
  read: (ids: string[]) => api<void>('/api/notifications/read', { method: 'POST', ...json({ ids }) }),
}
