import { getSchema } from '@tiptap/core'
import { EditorState } from '@tiptap/pm/state'
import { describe, it } from 'vitest'
import { Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { insertBlock, textOp } from '../ot/op'
import { Delta } from '../ot/delta'
import * as A from '../ot/attrs'
import { RichText } from '../ot/richText'
import { renderView, toPm } from './model'
import { schemaExtensions } from './schema'

/*
 * 원격 메시지 하나를 편집기에 반영하는 비용 (진단 PERF-1). `npm run perf` 로 돌린다. 시험(npm test)에는 들어가지 않는다. 시간을
 * 단언하지 않고 중앙값을 찍는다. 기기마다 값이 달라 시험으로 고정하면 흔들린다.
 * 블록 n 개 중 다섯에 하나는 앞 블록의 자식이다.
 */
const schema = getSchema(schemaExtensions)

function documentOf(n: number): Document {
  let doc = Document.create('root')
  const ops = []
  let last: string | null = null
  let lastParent: string | null = null
  let childLast: string | null = null
  for (let i = 0; i < n; i++) {
    const nested = i % 5 === 4 && lastParent !== null
    const parent = nested ? (lastParent as string) : 'root'
    const pos: string = nested ? between(childLast, null) : between(last, null)
    ops.push(insertBlock(`b${i}`, parent, pos, 'paragraph', A.NO_ATTRS, RichText.of(`문단 ${i} 의 글`)))
    if (nested) {
      childLast = pos
    } else {
      last = pos
      lastParent = `b${i}`
      childLast = null
    }
  }
  doc = doc.apply(ops)
  return doc
}

/** fn 을 여러 번 돌려 중앙값(ms). 처음 몇 번은 버린다(JIT). */
function median(fn: () => void, runs: number): number {
  for (let i = 0; i < 3; i++) {
    fn()
  }
  const times: number[] = []
  for (let i = 0; i < runs; i++) {
    const start = performance.now()
    fn()
    times.push(performance.now() - start)
  }
  times.sort((a, b) => a - b)
  return times[Math.floor(times.length / 2)] as number
}

describe('원격 편집 하나 반영 (PERF-1)', () => {
  for (const n of [500, 2_000, 5_000]) {
    it(`블록 ${n}개`, () => {
      const doc = documentOf(n)
      const state = EditorState.create({ schema, doc: toPm(schema, doc) })
      // 원격 편집 하나: 가운데 블록에 글자 하나
      const edited = doc.apply([textOp(`b${Math.floor(n / 2)}`, Delta.builder().insert('가').build())])
      const runs = n >= 5_000 ? 15 : 30
      const pm = median(() => toPm(schema, edited), runs)
      const render = median(() => renderView(state, edited), runs)
      process.stdout.write(`PERF 블록 ${n}개: toPm ${pm.toFixed(1)}ms, renderView ${render.toFixed(1)}ms\n`)
    })
  }
})
