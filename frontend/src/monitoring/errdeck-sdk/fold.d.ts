import type { ErrDeckEvent } from './types';
/**
 * 같은 에러를 접는다(11.1). 같은 스택의 에러가 구간 안에 기준을 넘으면 이후는 횟수만 세고, 구간이 끝날 때 대표 한 건에 횟수를 싣는다.
 * 사용자가 버튼을 연타하거나 반복 렌더링에서 같은 에러가 쏟아질 때 네트워크를 지킨다. 기억하는 스택 종류에 상한을 둔다.
 */
export declare class Folder {
    private readonly windowMs;
    private readonly threshold;
    private readonly maxSignatures;
    private readonly windows;
    constructor(windowMs: number, threshold: number, maxSignatures?: number);
    /** 그대로 보내면 true. */
    admit(event: ErrDeckEvent, now: number): boolean;
    private retired;
    /** 구간이 끝난 것의 대표 건. */
    drain(now: number): ErrDeckEvent[];
}
export declare function uuid(): string;
