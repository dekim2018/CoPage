package com.copage.collab.app;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 맡긴 일을 받은 순서대로 하나씩 실행한다. 실제 실행은 delegate 가 한다.
 *
 * <p>문서 세션마다 하나씩 둔다. 한 문서의 일은 한 번에 하나만 돌아서 락 없이 순서가 정해지고, 쉬는 세션은 스레드를 잡고
 * 있지 않다 (docs/03-ARCHITECTURE.md 5.2).
 */
public final class SerialExecutor implements Executor {

    private static final System.Logger LOG = System.getLogger(SerialExecutor.class.getName());

    private final Executor delegate;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean running;

    public SerialExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        synchronized (this) {
            tasks.add(task);
            if (running) {
                return;
            }
            running = true;
        }
        try {
            delegate.execute(this::drain);
        } catch (RejectedExecutionException e) {
            // 서버가 내려가는 중이라 실행기가 닫혔다. 남은 일은 버린다.
            synchronized (this) {
                tasks.clear();
                running = false;
            }
            LOG.log(System.Logger.Level.DEBUG, "실행기가 닫혀 일을 버렸다", e);
        }
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (this) {
                next = tasks.poll();
                if (next == null) {
                    running = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                // 한 일이 실패해도 뒤에 쌓인 일은 계속 돈다. 세션의 일은 스스로 예외를 처리하므로 여기 오면 버그다.
                LOG.log(System.Logger.Level.ERROR, "직렬 실행 중 처리하지 못한 예외", e);
            }
        }
    }
}
