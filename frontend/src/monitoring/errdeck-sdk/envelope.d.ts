import type { ErrDeckTransaction } from './tracing';
import type { ErrDeckEvent } from './types';
export declare const SDK = "vue/0.1.0";
export interface Discarded {
    reason: string;
    category: string;
    quantity: number;
}
/**
 * 줄 단위 JSON 봉투(5.3). 항목 머리 줄의 length 는 본문의 UTF-8 바이트 수다. 한글 메시지가 있어도 core 가 같은 경계로 자른다.
 */
export declare function envelope(events: ErrDeckEvent[], discarded: Discarded[], now?: Date, transactions?: ErrDeckTransaction[]): Uint8Array;
/** 브라우저의 CompressionStream 으로 gzip 한다. 없으면 null. */
export declare function gzip(data: Uint8Array): Promise<Uint8Array | null>;
