/** 페이지 권한 등급 (docs/07-SECURITY.md 9.4). 뒤로 갈수록 높다. */
export type PageRole = 'VIEW' | 'COMMENT' | 'EDIT' | 'FULL'

const ORDER: readonly PageRole[] = ['VIEW', 'COMMENT', 'EDIT', 'FULL']

/** 본문을 고칠 수 있는 등급인지. COMMENT 는 댓글만 단다. */
export function canEdit(role: PageRole | null): boolean {
  return role !== null && ORDER.indexOf(role) >= ORDER.indexOf('EDIT')
}

/** 서버가 보낸 값을 등급으로 읽는다. 모르는 값이면 가장 낮은 등급으로 본다. */
export function parseRole(value: unknown): PageRole {
  return ORDER.includes(value as PageRole) ? (value as PageRole) : 'VIEW'
}
