import * as A from './attrs'
import type { Attrs, AttrValue } from './attrs'
import { del, Delta, insert, retain } from './delta'
import type { Component } from './delta'
import { block, Document } from './document'
import type { Block } from './document'
import { InvalidArgumentError } from './errors'
import { assertNever, deleteBlock, insertBlock, mergeBlock, moveBlock, setProps, splitBlock, textOp } from './op'
import type { Op } from './op'
import { RichText } from './richText'

/*
 * 연산과 문서의 JSON 형식. Java 쪽 적합성 벡터 생성기(VectorJson)와 같은 모양이다.
 *
 * 연산은 docs/05-PROTOCOL.md 7.2 의 형식({"op": "text", "blockId": ..., "delta": [...]})을 따른다. 문서는 블록을 ID 순으로
 * 늘어놓은 배열로 적는다. 서식과 속성의 키는 이름 순이고, 조각과 글의 서식은 비어 있으면 적지 않는다.
 */

export type AttrsJson = { [key: string]: AttrValue }

export type ComponentJson =
  | { retain: number; attrs?: AttrsJson }
  | { insert: string; attrs?: AttrsJson }
  | { delete: number }

export type SegmentJson = { insert: string; attrs?: AttrsJson }

export type OpJson =
  | { op: 'text'; blockId: string; delta: ComponentJson[] }
  | {
      op: 'split'
      blockId: string
      offset: number
      newBlockId: string
      parentId: string
      pos: string
      type: string
      props: AttrsJson
    }
  | { op: 'merge'; blockId: string; intoId: string; intoLength: number }
  | {
      op: 'insertBlock'
      blockId: string
      parentId: string
      pos: string
      type: string
      props: AttrsJson
      text: SegmentJson[]
    }
  | { op: 'deleteBlock'; blockId: string; deleted: boolean }
  | { op: 'moveBlock'; blockId: string; parentId: string; pos: string }
  | { op: 'setProps'; blockId: string; key: string; value: AttrValue }

export interface BlockJson {
  id: string
  type: string
  props: AttrsJson
  text: SegmentJson[]
  parentId: string | null
  pos: string
  deleted: boolean
}

export interface DocumentJson {
  root: string
  blocks: BlockJson[]
}

// ----- 내보내기 -----

export function encodeAttrs(attrs: Attrs): AttrsJson {
  return { ...A.copy(attrs) }
}

export function encodeRichText(text: RichText): SegmentJson[] {
  return text.segments.map((s) => (A.isEmpty(s.attrs) ? { insert: s.text } : { insert: s.text, attrs: encodeAttrs(s.attrs) }))
}

export function encodeComponents(components: readonly Component[]): ComponentJson[] {
  return components.map((c): ComponentJson => {
    switch (c.kind) {
      case 'retain':
        return A.isEmpty(c.attrs) ? { retain: c.count } : { retain: c.count, attrs: encodeAttrs(c.attrs) }
      case 'insert':
        return A.isEmpty(c.attrs) ? { insert: c.text } : { insert: c.text, attrs: encodeAttrs(c.attrs) }
      case 'delete':
        return { delete: c.count }
    }
  })
}

export function encodeDelta(delta: Delta): ComponentJson[] {
  return encodeComponents(delta.components)
}

export function encodeOp(op: Op): OpJson {
  switch (op.op) {
    case 'text':
      return { op: 'text', blockId: op.blockId, delta: encodeDelta(op.delta) }
    case 'split':
      return {
        op: 'split',
        blockId: op.blockId,
        offset: op.offset,
        newBlockId: op.newBlockId,
        parentId: op.parentId,
        pos: op.pos,
        type: op.type,
        props: encodeAttrs(op.props),
      }
    case 'merge':
      return { op: 'merge', blockId: op.blockId, intoId: op.intoId, intoLength: op.intoLength }
    case 'insertBlock':
      return {
        op: 'insertBlock',
        blockId: op.blockId,
        parentId: op.parentId,
        pos: op.pos,
        type: op.type,
        props: encodeAttrs(op.props),
        text: encodeRichText(op.text),
      }
    case 'deleteBlock':
      return { op: 'deleteBlock', blockId: op.blockId, deleted: op.deleted }
    case 'moveBlock':
      return { op: 'moveBlock', blockId: op.blockId, parentId: op.parentId, pos: op.pos }
    case 'setProps':
      return { op: 'setProps', blockId: op.blockId, key: op.key, value: op.value }
    default:
      return assertNever(op)
  }
}

export function encodeOps(ops: readonly Op[]): OpJson[] {
  return ops.map(encodeOp)
}

export function encodeBlock(b: Block): BlockJson {
  return {
    id: b.id,
    type: b.type,
    props: encodeAttrs(b.props),
    text: encodeRichText(b.text),
    parentId: b.parentId,
    pos: b.pos,
    deleted: b.deleted,
  }
}

export function encodeDocument(doc: Document): DocumentJson {
  const blocks = [...doc.blocks()].sort((x, y) => A.compareStrings(x.id, y.id))
  return { root: doc.rootId, blocks: blocks.map(encodeBlock) }
}

// ----- 읽기 -----

function fail(message: string): never {
  throw new InvalidArgumentError(`JSON 형식이 맞지 않다: ${message}`)
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function str(value: unknown, field: string): string {
  return typeof value === 'string' ? value : fail(`${field} 는 문자열이어야 한다`)
}

function int(value: unknown, field: string): number {
  return typeof value === 'number' && Number.isInteger(value) ? value : fail(`${field} 는 정수여야 한다`)
}

function bool(value: unknown, field: string): boolean {
  return typeof value === 'boolean' ? value : fail(`${field} 는 참거짓이어야 한다`)
}

function list(value: unknown, field: string): unknown[] {
  return Array.isArray(value) ? value : fail(`${field} 는 배열이어야 한다`)
}

export function decodeAttrs(value: unknown): Attrs {
  if (value === undefined) {
    return A.NO_ATTRS
  }
  return isObject(value) ? A.copy(value as Attrs) : fail('attrs 는 객체여야 한다')
}

export function decodeRichText(value: unknown): RichText {
  return RichText.ofSegments(
    list(value, 'text').map((item) => {
      if (!isObject(item)) {
        fail('글 구간은 객체여야 한다')
      }
      return { text: str(item.insert, 'insert'), attrs: decodeAttrs(item.attrs) }
    }),
  )
}

/** 조각 목록을 정규화하지 않고 읽는다. */
export function decodeComponents(value: unknown): Component[] {
  return list(value, 'delta').map((item) => {
    if (!isObject(item)) {
      fail('조각은 객체여야 한다')
    }
    if ('retain' in item) {
      return retain(int(item.retain, 'retain'), decodeAttrs(item.attrs))
    }
    if ('insert' in item) {
      return insert(str(item.insert, 'insert'), decodeAttrs(item.attrs))
    }
    if ('delete' in item) {
      return del(int(item.delete, 'delete'))
    }
    return fail('조각은 retain, insert, delete 중 하나여야 한다')
  })
}

export function decodeDelta(value: unknown): Delta {
  return Delta.of(decodeComponents(value))
}

export function decodeOp(value: unknown): Op {
  if (!isObject(value)) {
    return fail('연산은 객체여야 한다')
  }
  const blockId = str(value.blockId, 'blockId')
  switch (value.op) {
    case 'text':
      return textOp(blockId, decodeDelta(value.delta))
    case 'split':
      return splitBlock(
        blockId,
        int(value.offset, 'offset'),
        str(value.newBlockId, 'newBlockId'),
        str(value.parentId, 'parentId'),
        str(value.pos, 'pos'),
        str(value.type, 'type'),
        decodeAttrs(value.props),
      )
    case 'merge':
      return mergeBlock(blockId, str(value.intoId, 'intoId'), int(value.intoLength, 'intoLength'))
    case 'insertBlock':
      return insertBlock(
        blockId,
        str(value.parentId, 'parentId'),
        str(value.pos, 'pos'),
        str(value.type, 'type'),
        decodeAttrs(value.props),
        value.text === undefined ? RichText.EMPTY : decodeRichText(value.text),
      )
    case 'deleteBlock':
      return deleteBlock(blockId, bool(value.deleted, 'deleted'))
    case 'moveBlock':
      return moveBlock(blockId, str(value.parentId, 'parentId'), str(value.pos, 'pos'))
    case 'setProps':
      if (!('value' in value)) {
        fail('setProps 에 value 가 없다')
      }
      return setProps(blockId, str(value.key, 'key'), value.value as AttrValue)
    default:
      return fail(`알 수 없는 연산 ${String(value.op)}`)
  }
}

export function decodeOps(value: unknown): Op[] {
  return list(value, 'ops').map(decodeOp)
}

export function decodeDocument(value: unknown): Document {
  if (!isObject(value)) {
    return fail('문서는 객체여야 한다')
  }
  const blocks = list(value.blocks, 'blocks').map((item) => {
    if (!isObject(item)) {
      fail('블록은 객체여야 한다')
    }
    return block(
      str(item.id, 'id'),
      str(item.type, 'type'),
      decodeAttrs(item.props),
      decodeRichText(item.text),
      item.parentId === null ? null : str(item.parentId, 'parentId'),
      str(item.pos, 'pos'),
      bool(item.deleted, 'deleted'),
    )
  })
  return Document.of(str(value.root, 'root'), blocks)
}
