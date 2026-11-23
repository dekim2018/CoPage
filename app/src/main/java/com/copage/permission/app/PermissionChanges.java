package com.copage.permission.app;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 권한 변경을 듣는 쪽 모두에게 알린다 (D-17). 듣는 쪽 하나가 실패해도 나머지에는 알리고, 실패는 기록만 한다. 이미 커밋한 변경을
 * 되돌릴 수는 없기 때문이다. 알리지 못한 연결은 다음 재접속 때 새 티켓으로 바른 등급을 받는다.
 */
public final class PermissionChanges implements Consumer<PermissionChange> {

    private static final System.Logger LOG = System.getLogger(PermissionChanges.class.getName());

    private final Supplier<List<PermissionChangeListener>> listeners;

    public PermissionChanges(Supplier<List<PermissionChangeListener>> listeners) {
        this.listeners = listeners;
    }

    @Override
    public void accept(PermissionChange change) {
        for (PermissionChangeListener listener : listeners.get()) {
            try {
                listener.changed(change);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "권한 변경을 알리지 못했다: " + listener, e);
            }
        }
    }
}
