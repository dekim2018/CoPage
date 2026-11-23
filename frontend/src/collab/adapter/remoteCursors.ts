import { Extension } from '@tiptap/core'
import type { Node as PmNode } from '@tiptap/pm/model'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import type { Peer } from '../client/presence'
import { toPmPosition } from './model'

/*
 * 남의 커서와 선택 영역을 그린다 (docs/05-PROTOCOL.md 7.6). 위치는 (블록 ID, 블록 안 위치)로 들고 있다가, 편집기 문서가 바뀔
 * 때마다 지금 문서에서 다시 찾는다. 블록이 없으면(지워졌거나 접힌 토글 안) 그리지 않는다.
 */

export const remoteCursorsKey = new PluginKey<readonly Peer[]>('copageRemoteCursors')

function decorations(doc: PmNode, peers: readonly Peer[]): DecorationSet {
  const out: Decoration[] = []
  for (const peer of peers) {
    if (peer.cursor === null) {
      continue
    }
    const head = toPmPosition(doc, peer.cursor.head)
    const anchor = toPmPosition(doc, peer.cursor.anchor)
    if (head === null) {
      continue
    }
    if (anchor !== null && anchor !== head) {
      out.push(
        Decoration.inline(Math.min(anchor, head), Math.max(anchor, head), {
          class: 'remote-selection',
          style: `background-color: ${peer.color}33`,
        }),
      )
    }
    out.push(
      Decoration.widget(head, () => caret(peer), {
        key: `${peer.clientId}:${peer.name}:${peer.color}`,
        side: -1,
        ignoreSelection: true,
      }),
    )
  }
  return DecorationSet.create(doc, out)
}

/** 이름표는 CSS(::after 의 attr(data-name))로 그린다. 글자 노드로 넣으면 블록의 글(textContent)에 남의 이름이 섞인다. */
function caret(peer: Peer): HTMLElement {
  const caret = document.createElement('span')
  caret.className = 'remote-caret'
  caret.style.setProperty('--peer-color', peer.color)
  caret.dataset.clientId = peer.clientId
  caret.dataset.name = peer.name
  return caret
}

/** 남의 커서 목록은 이 확장의 플러그인 상태에 둔다. 바인딩이 트랜잭션 메타로 바꾼다. */
export const RemoteCursors = Extension.create({
  name: 'copageRemoteCursors',
  addProseMirrorPlugins() {
    return [
      new Plugin<readonly Peer[]>({
        key: remoteCursorsKey,
        state: {
          init: () => [],
          apply: (tr, peers) => (tr.getMeta(remoteCursorsKey) as readonly Peer[] | undefined) ?? peers,
        },
        props: {
          decorations: (state) => decorations(state.doc, remoteCursorsKey.getState(state) ?? []),
        },
      }),
    ]
  },
})
