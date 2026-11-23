/*
 * ot-core(Java, com.copage.ot)의 TypeScript 구현. 두 구현은 ot-core 가 내보내는 적합성 벡터로 같은 결과를 내는지 확인한다.
 * 브라우저와 Vue 를 모르는 순수 TypeScript 라서 Vitest 에서 그대로 돌린다.
 */
export * as Attrs from './attrs'
export type { AttrValue } from './attrs'
export * as Changes from './changes'
export { Delta, DeltaBuilder } from './delta'
export type { Component, Delete, Insert, Retain } from './delta'
export { Document, ROOT_TYPE } from './document'
export type { Block } from './document'
export { InvalidArgumentError, InvalidOperationError, TransformConflictError } from './errors'
export * as FractionalIndex from './fractionalIndex'
export * as Inverter from './inverter'
export * as Json from './json'
export * from './op'
export { RichText, RichTextBuilder } from './richText'
export type { Segment } from './richText'
export * as Transformer from './transformer'
export type { TransformPair } from './transformer'
