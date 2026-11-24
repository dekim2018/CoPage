package com.copage.loadgen;

import com.copage.ot.FractionalIndex;
import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.text.Attrs;
import com.copage.ot.text.RichText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 부하 시나리오(docs/11-TEST-PLAN.md 13.3)를 돌린다. 인자는 key=value 다.
 *
 * <ul>
 *   <li>scenario=one-doc editors=N seconds=S: 한 문서에 N 명이 초당 5 글자씩 S 초 친다
 *   <li>scenario=many-docs docs=D editors=E seconds=S: D 개 문서에 E 명씩
 *   <li>scenario=open-doc blocks=B opens=O: B 블록 문서를 O 번 열어 welcome 까지 시간을 잰다
 * </ul>
 *
 * <p>reconnect=true 면 끊겼을 때 다시 붙는다(서버 강제 종료 실험).
 *
 * <p>base(기본 http://localhost:8080), management(기본 http://127.0.0.1:8081, 관리 포트는 127.0.0.1 에만 열린다), rate(초당 누름, 기본 5).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            options.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        String base = options.getOrDefault("base", "http://localhost:8080");
        String management = options.getOrDefault("management", "http://127.0.0.1:8081");
        String scenario = options.getOrDefault("scenario", "one-doc");
        Api api = new Api(base);
        String wsBase = base.replaceFirst("^http", "ws");
        switch (scenario) {
            case "one-doc" -> typing(api, wsBase, management, 1, integer(options, "editors", 10),
                    integer(options, "seconds", 30), integer(options, "rate", 5), reconnect(options),
                    flag(options, "lateJoin"));
            case "many-docs" -> typing(api, wsBase, management, integer(options, "docs", 20),
                    integer(options, "editors", 5), integer(options, "seconds", 30), integer(options, "rate", 5),
                    reconnect(options), flag(options, "lateJoin"));
            case "open-doc" -> open(api, wsBase, management, integer(options, "blocks", 1000), integer(options, "opens", 20),
                    Boolean.parseBoolean(options.getOrDefault("cold", "false")));
            default -> throw new IllegalArgumentException("모르는 시나리오: " + scenario);
        }
        System.exit(0);
    }

    private static boolean flag(Map<String, String> options, String key) {
        return Boolean.parseBoolean(options.getOrDefault(key, "false"));
    }

    private static boolean reconnect(Map<String, String> options) {
        return Boolean.parseBoolean(options.getOrDefault("reconnect", "false"));
    }

    private static int integer(Map<String, String> options, String key, int fallback) {
        return options.containsKey(key) ? Integer.parseInt(options.get(key)) : fallback;
    }

    private static void typing(Api api, String wsBase, String management, int docs, int editorsPerDoc, int seconds,
            int rate, boolean reconnect, boolean lateJoin) throws Exception {
        VirtualEditor.Shared shared = new VirtualEditor.Shared();
        // 계정은 문서마다 만들지 않고 편집자 자리마다 하나씩 만들어 모든 문서에서 함께 쓴다. 가입(bcrypt)이 준비 시간을 잡아먹지 않게.
        List<Api.Account> accounts = new ArrayList<>();
        for (int e = 0; e < editorsPerDoc; e++) {
            accounts.add(api.account("e" + e));
        }
        Api.Account owner = accounts.get(0);
        String workspace = api.workspace(owner);
        accounts.stream().skip(1).forEach(member -> api.addMember(owner, workspace, member));
        double heapBefore = api.metric(management, "jvm_memory_used_bytes{area=\"heap\"");
        List<List<VirtualEditor>> groups = new ArrayList<>();
        List<String> pages = new ArrayList<>();
        for (int d = 0; d < docs; d++) {
            String page = api.page(owner, workspace);
            pages.add(page);
            List<VirtualEditor> group = new ArrayList<>();
            for (int e = 0; e < editorsPerDoc; e++) {
                VirtualEditor editor = new VirtualEditor("d" + d + "e" + e, page, shared, d * 1000L + e);
                Api.Account who = accounts.get(e);
                editor.connect(wsBase, api.ticket(who, page));
                if (reconnect) {
                    editor.reconnectWith(() -> api.ticket(who, page));
                }
                group.add(editor);
            }
            groups.add(group);
        }
        List<VirtualEditor> all = groups.stream().flatMap(List::stream).toList();
        System.out.printf("편집자 %d 명(%d 문서 x %d) 접속, %d 초 동안 초당 %d 번 누른다%n", all.size(), docs, editorsPerDoc,
                seconds, rate);

        double ackSumBefore = api.metric(management, "collab_ack_latency_seconds_sum");
        double ackCountBefore = api.metric(management, "collab_ack_latency_seconds_count");
        ScheduledExecutorService timer = Executors.newScheduledThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        long period = 1000L / rate;
        for (VirtualEditor editor : all) {
            long jitter = ThreadLocalRandom.current().nextLong(period);
            timer.scheduleAtFixedRate(guard(editor::typeOnce), jitter, period, TimeUnit.MILLISECONDS);
            timer.scheduleAtFixedRate(guard(editor::flush), jitter, 50, TimeUnit.MILLISECONDS);
        }
        if (lateJoin) {
            // 노드 추가 실험: 중간에 문서마다 한 명씩 더 들어온다. 새 연결은 프록시가 지금의 노드 구성으로 보낸다(D-43).
            Thread.sleep(seconds * 500L);
            System.out.println("문서마다 한 명씩 더 들어온다");
            for (int d = 0; d < groups.size(); d++) {
                VirtualEditor late = new VirtualEditor("d" + d + "late", pages.get(d), shared, d * 1000L + 999);
                String page = pages.get(d);
                // 첫 연결이 양도 중이라 4009 로 닫혀도 다시 붙는다. connect 는 welcome 까지 기다린다.
                late.reconnectWith(() -> api.ticket(owner, page));
                late.connect(wsBase, api.ticket(owner, page));
                groups.get(d).add(late);
                long jitter = ThreadLocalRandom.current().nextLong(period);
                timer.scheduleAtFixedRate(guard(late::typeOnce), jitter, period, TimeUnit.MILLISECONDS);
                timer.scheduleAtFixedRate(guard(late::flush), jitter, 50, TimeUnit.MILLISECONDS);
            }
            all = groups.stream().flatMap(List::stream).toList();
            Thread.sleep(seconds * 500L);
        } else {
            Thread.sleep(seconds * 1000L);
        }
        timer.shutdownNow();
        timer.awaitTermination(5, TimeUnit.SECONDS);

        // 남은 편집을 다 보내고 모두 같은 리비전에 이를 때까지 기다린다.
        long settleStart = System.currentTimeMillis();
        boolean settled = false;
        while (System.currentTimeMillis() - settleStart < 60_000) {
            all.forEach(VirtualEditor::flush);
            settled = groups.stream().allMatch(Main::settled);
            if (settled) {
                break;
            }
            Thread.sleep(50);
        }
        long converged = groups.stream().filter(group -> settled(group) && sameView(group)).count();
        double ackSum = api.metric(management, "collab_ack_latency_seconds_sum") - ackSumBefore;
        double ackCount = api.metric(management, "collab_ack_latency_seconds_count") - ackCountBefore;
        double serverMax = api.metric(management, "collab_ack_latency_seconds_max");

        System.out.println("보낸 묶음 " + shared.changes.get() + ", nack " + shared.nacks.get() + ", 닫힘 " + shared.closes);
        if (reconnect) {
            System.out.println("재개(끊김부터 다시 붙어 첫 ack 까지) " + shared.resume.summary() + ", 대기 편집을 버린 재접속 "
                    + shared.resets.get());
        }
        System.out.println("ack 지연(클라이언트)    " + shared.ack.summary());
        System.out.println("남에게 닿는 지연       " + shared.remote.summary());
        System.out.printf("서버 ack 처리 평균 %.2fms (n=%.0f), 최근 최대 %.1fms%n", ackSum / ackCount * 1000, ackCount,
                serverMax * 1000);
        System.out.printf("서버: 세션 %.0f, 연결 %.0f, 힙 %.0fMB (접속 전 %.0fMB), 프로세스 CPU %.0f%%%n",
                api.metric(management, "collab_sessions"), api.metric(management, "collab_connections"),
                api.metric(management, "jvm_memory_used_bytes{area=\"heap\"") / 1e6, heapBefore / 1e6,
                api.metric(management, "process_cpu_usage") * 100);
        System.out.println("수렴: " + converged + "/" + groups.size() + " 문서" + (settled ? "" : " (60초 안에 다 확정되지 않음)"));
        all.forEach(VirtualEditor::close);
        if (converged != groups.size()) {
            System.exit(1);
        }
    }

    private static boolean settled(List<VirtualEditor> group) {
        long rev = group.get(0).confirmedRev();
        return group.stream().allMatch(editor -> !editor.hasPending() && editor.confirmedRev() == rev);
    }

    private static boolean sameView(List<VirtualEditor> group) {
        Document first = group.get(0).view();
        return group.stream().allMatch(editor -> editor.view().equals(first));
    }

    /**
     * cold 면 채운 뒤 서버가 세션을 내릴 때까지(sessionUnloadDelay, 기본 60초) 기다려 첫 열기가 스냅샷에서 복구하게 한다.
     * 첫 열기(복구 포함)와 나머지(메모리의 세션)를 따로 적는다.
     */
    private static void open(Api api, String wsBase, String management, int blocks, int opens, boolean cold)
            throws Exception {
        Api.Account owner = api.account("owner");
        String workspace = api.workspace(owner);
        String page = api.page(owner, workspace);
        VirtualEditor.Shared fillShared = new VirtualEditor.Shared();
        VirtualEditor filler = new VirtualEditor("filler", page, fillShared, 1);
        filler.connect(wsBase, api.ticket(owner, page));
        String pos = null;
        for (int start = 0; start < blocks; start += 100) {
            List<Op> batch = new ArrayList<>();
            for (int i = start; i < Math.min(blocks, start + 100); i++) {
                pos = FractionalIndex.between(pos, null);
                batch.add(new InsertBlock("blk" + i, page, pos, "paragraph", Attrs.NONE,
                        RichText.of("문단 " + i + " 에 들어가는 평범한 글 한 줄이다. 길이를 조금 갖춘다.")));
            }
            filler.submit(batch);
            while (filler.hasPending()) {
                filler.flush();
                Thread.sleep(20);
            }
        }
        filler.close();
        System.out.println(blocks + " 블록 문서를 채웠다. " + opens + " 번 연다");
        if (cold) {
            long waitStart = System.currentTimeMillis();
            while (api.metric(management, "collab_sessions") > 0 && System.currentTimeMillis() - waitStart < 180_000) {
                Thread.sleep(1_000);
            }
            System.out.printf("세션이 내려갔다(%.0f초 기다림)%n", (System.currentTimeMillis() - waitStart) / 1000.0);
        }
        double loadCountBefore = api.metric(management, "collab_session_load_seconds_count");
        double loadSumBefore = api.metric(management, "collab_session_load_seconds_sum");

        VirtualEditor.Shared first = new VirtualEditor.Shared();
        VirtualEditor.Shared shared = new VirtualEditor.Shared();
        for (int i = 0; i < opens; i++) {
            VirtualEditor reader = new VirtualEditor("reader" + i, page, i == 0 ? first : shared, i, false);
            reader.connect(wsBase, api.ticket(owner, page));
            reader.close();
        }
        double loads = api.metric(management, "collab_session_load_seconds_count") - loadCountBefore;
        double loadSum = api.metric(management, "collab_session_load_seconds_sum") - loadSumBefore;
        System.out.println("첫 열기(접속부터 welcome 까지)   " + first.open.summary());
        System.out.println("나머지 열기                      " + shared.open.summary());
        System.out.printf("서버 세션 복구 %.0f 번, 평균 %.1fms%n", loads, loads > 0 ? loadSum / loads * 1000 : 0.0);
    }

    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                System.err.println("편집자 작업 실패: " + e);
            }
        };
    }
}
