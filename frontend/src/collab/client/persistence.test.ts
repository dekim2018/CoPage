import { describe, expect, it } from 'vitest'
import { Delta } from '../ot/delta'
import { Document } from '../ot/document'
import { between } from '../ot/fractionalIndex'
import { insertBlock, textOp } from '../ot/op'
import { RichText } from '../ot/richText'
import { ClientSession } from './clientSession'

function base(): Document {
  return Document.create('root').apply([insertBlock('p', 'root', between(null, null), 'paragraph', {}, RichText.of('가'))])
}

function type(session: ClientSession, text: string) {
  const length = (session.view().get('p') as { text: RichText }).text.length
  session.edit([textOp('p', Delta.builder().retain(length).insert(text).build())])
}

describe('브라우저에 남긴 상태 기계 (12.4)', () => {
  it('보내지 못한 편집(inflight 와 buffer)을 남겼다 되살리면 화면과 순번이 같다', () => {
    const session = new ClientSession('tab-1', base(), 3)
    type(session, '나')
    const sent = session.poll()
    type(session, '다')

    const restored = ClientSession.restore(JSON.parse(JSON.stringify(session.export())))

    expect(restored.clientId).toBe('tab-1')
    expect(restored.confirmedRev()).toBe(3)
    expect(restored.view().equals(session.view())).toBe(true)
    expect(restored.hasPending()).toBe(true)
    // 다시 붙으면 예전 inflight 를 같은 순번으로 다시 보낸다.
    const resent = restored.resync([])
    expect(resent?.clientSeq).toBe(sent?.clientSeq)
    expect(resent?.ops).toEqual(sent?.ops)
  })

  it('끊기기 전에 서버가 이미 확정한 inflight 는 다시 보내지 않고, 다음 순번은 이어서 쓴다', () => {
    const session = new ClientSession('tab-1', base(), 3)
    type(session, '나')
    const sent = session.poll()
    type(session, '다')
    const restored = ClientSession.restore(session.export())

    // 서버에 들어간 뒤 ack 를 받기 전에 끊겼다. welcome 의 놓친 묶음에 내 것이 있다.
    const next = restored.resync([
      { rev: 4, clientId: 'tab-1', clientSeq: sent?.clientSeq as number, ops: sent?.ops ?? [] },
    ])

    expect(next?.clientSeq).toBe((sent?.clientSeq as number) + 1)
    expect(restored.confirmedRev()).toBe(4)
    restored.onAck(next?.clientSeq as number, 5)
    expect(restored.hasPending()).toBe(false)
    expect((restored.confirmed().get('p') as { text: RichText }).text.plainText()).toBe('가나다')
  })

  it('남긴 동안 남이 편집했으면 다시 붙을 때 그에 맞춰 변환한다', () => {
    const session = new ClientSession('tab-1', base(), 3)
    type(session, '나')
    const restored = ClientSession.restore(session.export())

    const next = restored.resync([
      { rev: 4, clientId: 'other', clientSeq: 1, ops: [textOp('p', Delta.builder().insert('앞').build())] },
    ])

    expect(next?.baseRev).toBe(4)
    expect((restored.view().get('p') as { text: RichText }).text.plainText()).toBe('앞가나')
  })
})
