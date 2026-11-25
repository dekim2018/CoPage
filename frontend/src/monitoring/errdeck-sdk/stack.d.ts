/** core 의 스택 프레임과 같은 모양. 브라우저 프레임은 module 이 없다. */
export interface Frame {
    function?: string;
    filename?: string;
    lineno?: number;
    colno?: number;
}
/**
 * Error.stack 문자열을 프레임으로 바꾼다. 예외가 난 곳이 먼저 온다(6장 끝). 읽을 수 없는 줄은 건너뛴다.
 * 앱 프레임 판정은 하지 않는다. 소스맵으로 복원한 뒤 worker 가 node_modules 밖인지로 정한다(6.3).
 */
export declare function parseStack(stack: string | undefined, max?: number): Frame[];
