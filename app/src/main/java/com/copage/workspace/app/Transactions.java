package com.copage.workspace.app;

/** 여러 저장소 호출을 한 트랜잭션으로 묶는 포트. 같은 DataSource 를 쓰는 저장소는 바깥 트랜잭션에 참여한다. */
public interface Transactions {

    void run(Runnable work);
}
