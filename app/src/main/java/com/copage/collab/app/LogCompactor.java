package com.copage.collab.app;

import com.copage.ot.model.Document;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 연산 로그 정리 (06-DATA-MODEL 8.3, D-52). retention 보다 오래된 연산을 고정 스냅샷으로 접고 지운다. 주기 작업이 부른다.
 *
 * <ul>
 *   <li>지울 지점(R)은 retention 보다 먼저 확정된 마지막 리비전이다. R 의 문서를 고정 스냅샷으로 남겨, 그 뒤 시점과 최신 문서는
 *       지금처럼 열린다
 *   <li>R 이하를 가리키는 버전 중 이름 붙인 시점(NAMED)과 복원 시점(RESTORE)은 모두, 자동 시점(AUTO)은 하루(UTC)에 마지막 하나만
 *       남기고 그 리비전을 고정 스냅샷으로 남긴다. 나머지 자동 시점은 지운다
 *   <li>retention 은 rebaseMaxAge 보다 길다. 이어 붙을 수 있는 연결이 놓친 묶음은 늘 로그에 있고, 구멍이 있으면 세션이 rebase
 *       false 를 보낸다
 * </ul>
 *
 * <p>여러 노드가 함께 돌려도 결과가 같다(스냅샷은 있으면 고정으로 바꾸고, 지우기는 같은 행을 지울 뿐이다).
 */
public final class LogCompactor {

    private static final System.Logger LOG = System.getLogger(LogCompactor.class.getName());

    private final LogCompaction compaction;
    private final DocumentStore store;
    private final Clock clock;

    public LogCompactor(LogCompaction compaction, DocumentStore store, Clock clock) {
        this.compaction = compaction;
        this.store = store;
        this.clock = clock;
    }

    /** @return 정리한 문서 수 */
    public int compactOlderThan(Duration retention, int maxDocs) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(retention);
        int done = 0;
        for (UUID docId : compaction.docsWithOpsBefore(cutoff, maxDocs)) {
            try {
                if (compact(docId, cutoff, now)) {
                    done++;
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "문서 " + docId + " 의 연산 로그를 정리하지 못했다", e);
            }
        }
        return done;
    }

    boolean compact(UUID docId, Instant cutoff, Instant now) {
        OptionalLong last = compaction.lastRevBefore(docId, cutoff);
        if (last.isEmpty()) {
            return false;
        }
        long through = last.getAsLong();
        List<LogCompaction.Version> versions = compaction.versionsThrough(docId, through);
        Set<UUID> drop = new HashSet<>();
        Map<LocalDate, LogCompaction.Version> lastAutoOfDay = new HashMap<>();
        for (LogCompaction.Version version : versions) {
            if (version.kind() != VersionLog.Kind.AUTO) {
                continue;
            }
            LocalDate day = LocalDate.ofInstant(version.createdAt(), ZoneOffset.UTC);
            LogCompaction.Version kept = lastAutoOfDay.get(day);
            if (kept == null || version.rev() > kept.rev()) {
                if (kept != null) {
                    drop.add(kept.id());
                }
                lastAutoOfDay.put(day, version);
            } else {
                drop.add(version.id());
            }
        }
        Set<Long> keepRevs = new TreeSet<>();
        keepRevs.add(through);
        for (LogCompaction.Version version : versions) {
            if (!drop.contains(version.id())) {
                keepRevs.add(version.rev());
            }
        }
        Set<Long> existing = compaction.snapshotRevs(docId);
        Map<Long, Document> pins = new HashMap<>();
        Set<Long> pinExisting = new HashSet<>();
        for (long rev : keepRevs) {
            if (existing.contains(rev)) {
                pinExisting.add(rev);
                continue;
            }
            try {
                pins.put(rev, store.loadAt(docId, rev));
            } catch (RevisionGoneException e) {
                // 앞선 정리에서 스냅샷 없이 남은 버전. 열 수 없으니 버전도 지운다.
                versions.stream().filter(v -> v.rev() == rev).forEach(v -> drop.add(v.id()));
            }
        }
        compaction.compact(docId, through, pins, pinExisting, drop, now);
        return true;
    }
}
