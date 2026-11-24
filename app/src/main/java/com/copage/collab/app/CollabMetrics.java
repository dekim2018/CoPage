package com.copage.collab.app;

/**
 * 협업 지표를 남기는 포트 (docs/11-TEST-PLAN.md 13.4, 진단 OPS-1). 문서 ID 와 사용자 ID 는 받지 않는다. 지표 이름에 붙는 레이블의
 * 가짓수가 문서와 사용자 수만큼 늘어나지 않게 하기 위해서다.
 */
public interface CollabMetrics {

    /** 묶음을 받은 때부터 ack 를 보낸 때까지. 커밋을 기다린 시간이 들어 있다. */
    void ackLatency(long nanos);

    /** 묶음 하나를 확정하려고 변환한 횟수. 기준 리비전이 지금 리비전보다 뒤처진 만큼이다. */
    void transforms(long count);

    /** 거부한 묶음. reason 은 정해진 몇 가지 분류다(permission, input, empty, rejected). */
    void nack(String reason);

    /** 한 번에 커밋한 묶음 수. */
    void commitBatch(int size);

    /** 세션을 올리려고 스냅샷과 로그를 읽은 시간. */
    void sessionLoad(long nanos);

    /** 받은 change 메시지의 바이트 수. 저장 용량을 예측한다. */
    void changeBytes(int bytes);

    /** 지표를 남기지 않는다. 시험에서 쓴다. */
    CollabMetrics NONE = new CollabMetrics() {
        @Override
        public void ackLatency(long nanos) {
        }

        @Override
        public void transforms(long count) {
        }

        @Override
        public void nack(String reason) {
        }

        @Override
        public void commitBatch(int size) {
        }

        @Override
        public void sessionLoad(long nanos) {
        }

        @Override
        public void changeBytes(int bytes) {
        }
    };
}
