package com.copage.ot.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.support.Iterations;
import com.copage.ot.support.RandomOps;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 서버 하나와 클라이언트 여러 개를 가짜 네트워크로 잇고 무작위로 돌리는 수렴 시뮬레이션.
 *
 * <p>편집, 전송 지연, 연결 끊김(보낸 묶음이 사라지는 경우와 답만 사라지는 경우), 재접속, 예전 연결로 보낸 묶음이 재접속
 * 뒤에 늦게 처리되는 경우를 섞는다. 마지막에 메시지를 전부 흘려보낸 뒤 세 가지를 확인한다.
 *
 * <ol>
 *   <li>모든 클라이언트의 화면 문서와 확정 문서가 서버 문서와 같다.
 *   <li>서버의 확정 기록을 처음 문서에 차례로 적용하면 서버 문서가 된다. 즉 로그만으로 문서를 복구할 수 있다.
 *   <li>서버가 받아들인 묶음은 하나도 빠지지 않고 기록에 한 번씩만 있다.
 * </ol>
 */
class ConvergenceSimulationTest {

    @Test
    void 무작위_동시_편집과_끊김을_섞어도_모두_같은_문서로_수렴한다() {
        int runs = Iterations.of(1_500);
        Totals totals = new Totals();
        for (int i = 0; i < runs; i++) {
            new Simulation(70_000L + i, totals).run();
        }
        System.out.println("수렴 시뮬레이션 " + runs + "회: " + totals);
        assertTrue(totals.accepted > 0 && totals.rejected > 0 && totals.reconnects > 0, "모든 경로를 지나야 한다: " + totals);
    }

    private static final class Totals {
        long edits;
        long accepted;
        long rejected;
        long duplicates;
        long reconnects;

        @Override
        public String toString() {
            return "편집 " + edits + ", 확정 " + accepted + ", 거부 " + rejected + ", 중복 " + duplicates + ", 재접속 "
                    + reconnects;
        }
    }

    /** 서버가 클라이언트에게 보내는 메시지. */
    private sealed interface Message {
        record Change(CommittedChange change) implements Message {
        }

        record Ack(long clientSeq, long rev) implements Message {
        }

        record Nack(long clientSeq) implements Message {
        }
    }

    /** 서버로 가는 중인 묶음. epoch 는 보낼 때의 연결 번호다. 재접속하면 번호가 바뀐다. */
    private record InTransit(Submission submission, int epoch) {
    }

    private static final class Peer {
        final ClientSession session;
        final RandomOps generator;
        final Deque<InTransit> toServer = new ArrayDeque<>();
        final Deque<Message> toClient = new ArrayDeque<>();
        boolean connected = true;
        int epoch;

        Peer(ClientSession session, RandomOps generator) {
            this.session = session;
            this.generator = generator;
        }
    }

    private static final class Simulation {
        private final long seed;
        private final Random random;
        private final Totals totals;
        private final Document start;
        private final ServerDocument server;
        private final List<Peer> peers = new ArrayList<>();
        private final List<String> acceptedKeys = new ArrayList<>();

        Simulation(long seed, Totals totals) {
            this.seed = seed;
            this.random = new Random(seed);
            this.totals = totals;
            this.start = new RandomOps(random, "s").randomDocument(1 + random.nextInt(5));
            this.server = new ServerDocument(start, 0);
            int clients = 2 + random.nextInt(4);
            for (int i = 0; i < clients; i++) {
                String id = "c" + i;
                peers.add(new Peer(new ClientSession(id, start, 0), new RandomOps(random, id + "-")));
            }
        }

        void run() {
            try {
                int steps = 40 + random.nextInt(160);
                for (int i = 0; i < steps; i++) {
                    step();
                }
                drain();
                verify();
            } catch (RuntimeException | AssertionError e) {
                throw new AssertionError("시드 " + seed + " 에서 실패: " + e, e);
            }
        }

        private void step() {
            Peer peer = peers.get(random.nextInt(peers.size()));
            int roll = random.nextInt(100);
            if (roll < 35) {
                edit(peer);
            } else if (roll < 62) {
                serverReceives(peer);
            } else if (roll < 92) {
                clientReceives(peer);
            } else if (roll < 96) {
                disconnect(peer);
            } else {
                reconnect(peer);
            }
        }

        private void edit(Peer peer) {
            List<Op> change = peer.generator.randomChange(peer.session.view(), 3);
            peer.session.edit(change);
            totals.edits++;
            send(peer);
        }

        private void send(Peer peer) {
            if (peer.connected) {
                peer.session.poll().ifPresent(s -> peer.toServer.add(new InTransit(s, peer.epoch)));
            }
        }

        private void serverReceives(Peer sender) {
            InTransit transit = sender.toServer.poll();
            if (transit == null) {
                return;
            }
            Submission submission = transit.submission();
            boolean sameConnection = sender.connected && transit.epoch() == sender.epoch;
            switch (server.submit(submission)) {
                case SubmitResult.Accepted accepted -> {
                    totals.accepted++;
                    acceptedKeys.add(submission.clientId() + "#" + submission.clientSeq());
                    for (Peer peer : peers) {
                        if (!peer.connected) {
                            continue;
                        }
                        if (peer == sender && sameConnection) {
                            peer.toClient.add(new Message.Ack(submission.clientSeq(), accepted.change().rev()));
                        } else {
                            // 보낸 사람이 그 사이 재접속했다면, 새 연결은 다른 사람과 똑같이 change 를 받는다.
                            peer.toClient.add(new Message.Change(accepted.change()));
                        }
                    }
                }
                case SubmitResult.Duplicate duplicate -> {
                    totals.duplicates++;
                    if (sameConnection) {
                        sender.toClient.add(new Message.Ack(submission.clientSeq(), duplicate.rev()));
                    }
                }
                case SubmitResult.Rejected rejected -> {
                    totals.rejected++;
                    if (sameConnection) {
                        sender.toClient.add(new Message.Nack(submission.clientSeq()));
                    }
                }
            }
        }

        private void clientReceives(Peer peer) {
            Message message = peer.toClient.poll();
            if (message == null) {
                return;
            }
            switch (message) {
                case Message.Change change -> peer.session.onChange(change.change());
                case Message.Ack ack -> peer.session.onAck(ack.clientSeq(), ack.rev());
                case Message.Nack nack -> peer.session.onNack(nack.clientSeq());
            }
            send(peer);
        }

        private void disconnect(Peer peer) {
            if (!peer.connected) {
                return;
            }
            peer.connected = false;
            // 서버가 보낸 답은 사라진다. 클라이언트가 보낸 묶음은 사라지기도 하고 서버에 닿기도 한다.
            peer.toClient.clear();
            if (random.nextBoolean()) {
                peer.toServer.clear();
            }
        }

        private void reconnect(Peer peer) {
            if (peer.connected) {
                return;
            }
            totals.reconnects++;
            // 예전 연결로 보낸 묶음을 서버가 재접속 전에 처리하기도 하고, 재접속 뒤에 늦게 처리하기도 한다.
            if (random.nextBoolean()) {
                while (!peer.toServer.isEmpty()) {
                    serverReceives(peer);
                }
            }
            peer.connected = true;
            peer.epoch++;
            peer.session
                    .resync(server.changesSince(peer.session.confirmedRev()))
                    .ifPresent(s -> peer.toServer.add(new InTransit(s, peer.epoch)));
        }

        private void drain() {
            for (Peer peer : peers) {
                reconnect(peer);
            }
            boolean busy = true;
            int guard = 0;
            while (busy) {
                if (guard++ > 100_000) {
                    throw new IllegalStateException("메시지가 끝나지 않는다");
                }
                busy = false;
                for (Peer peer : peers) {
                    send(peer);
                    if (!peer.toServer.isEmpty()) {
                        serverReceives(peer);
                        busy = true;
                    }
                    if (!peer.toClient.isEmpty()) {
                        clientReceives(peer);
                        busy = true;
                    }
                    busy |= !peer.toServer.isEmpty() || !peer.toClient.isEmpty();
                }
            }
        }

        private void verify() {
            Document expected = server.document();
            for (Peer peer : peers) {
                String who = "클라이언트 " + peer.session.clientId();
                assertFalse(peer.session.hasPending(), who + " 에 보내지 못한 편집이 남았다");
                assertEquals(server.rev(), peer.session.confirmedRev(), who + " 리비전");
                assertEquals(expected, peer.session.confirmed(), who + " 확정 문서");
                assertEquals(expected, peer.session.view(), who + " 화면 문서");
            }
            Document replayed = start;
            List<String> loggedKeys = new ArrayList<>();
            for (CommittedChange change : server.changesSince(0)) {
                replayed = replayed.apply(change.ops());
                loggedKeys.add(change.clientId() + "#" + change.clientSeq());
            }
            assertEquals(expected, replayed, "로그를 다시 적용한 문서");
            assertEquals(acceptedKeys, loggedKeys, "받아들인 묶음과 기록");
            assertEquals(loggedKeys.size(), loggedKeys.stream().distinct().count(), "같은 묶음이 두 번 기록되었다");
        }
    }
}
