import { describe, expect, it } from 'vitest'
import { canEdit, parseRole } from './role'

describe('권한 등급', () => {
  it('EDIT 이상만 본문을 고친다', () => {
    expect(canEdit('FULL')).toBe(true)
    expect(canEdit('EDIT')).toBe(true)
    expect(canEdit('COMMENT')).toBe(false)
    expect(canEdit('VIEW')).toBe(false)
    expect(canEdit(null)).toBe(false)
  })

  it('모르는 값은 가장 낮은 등급으로 읽는다', () => {
    expect(parseRole('EDIT')).toBe('EDIT')
    expect(parseRole('OWNER')).toBe('VIEW')
    expect(parseRole(undefined)).toBe('VIEW')
  })
})
