import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useWorkspace } from './workspace'

describe('워크스페이스 스토어의 제목', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('이 탭에서 고친 제목만 기억한다. 고친 적 없는 페이지는 서버가 준 제목을 쓰도록 undefined 다', () => {
    const workspace = useWorkspace()

    expect(workspace.editedTitle('p1')).toBeUndefined()
    workspace.setTitle('p1', '회의록')
    expect(workspace.editedTitle('p1')).toBe('회의록')
    workspace.setTitle('p1', '')
    expect(workspace.editedTitle('p1')).toBe('')
    expect(workspace.editedTitle('p2')).toBeUndefined()
  })

  it('나가면(reset) 잊는다', () => {
    const workspace = useWorkspace()
    workspace.setTitle('p1', '회의록')
    workspace.reset()
    expect(workspace.editedTitle('p1')).toBeUndefined()
  })
})
