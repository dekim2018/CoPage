package com.copage.ot.vectors;

import com.copage.ot.model.Document;
import com.copage.ot.op.Op;
import com.copage.ot.support.RandomOps;
import com.copage.ot.sync.ClientSession;
import com.copage.ot.sync.CommittedChange;
import com.copage.ot.sync.ServerDocument;
import com.copage.ot.sync.Submission;
import com.copage.ot.sync.SubmitResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 클라이언트 상태 기계({@link ClientSession})의 벡터.
 *
 * <p>ConvergenceSimulationTest 와 같은 방식으로 서버 하나와 클라이언트 여러 개를 가짜 네트워크로 잇고 편집, 지연, 끊김,
 * 재접속을 무작위로 섞는다. 협업 서버가 문서 크기 한도 같은 자기 검사로 묶음을 거부하는 경우도 낮은 확률로 섞는다. 이 거부는
 * 클라이언트가 미리 알 수 없어서, 화면에 반영된 inflight 가 nack 를 받는 경로를 지나게 된다. 그동안 클라이언트마다 받은 호출(edit, poll, onChange, onAck, onNack, resync)과 호출 직후의
 * 상태를 순서대로 적는다. TypeScript 쪽은 클라이언트 하나씩 같은 호출을 차례로 재생하면서 상태를 비교한다.
 *
 * <p>화면 문서는 바뀌었을 때만 적는다. 재생하는 쪽은 마지막으로 적힌 화면 문서와 매번 비교한다.
 */
final class ClientVectors {

    private ClientVectors() {
    }

    static ArrayNode scenarios(int count, long seedBase) {
        ArrayNode out = VectorJson.array();
        for (int i = 0; i < count; i++) {
            out.add(new Simulation(seedBase + i).run());
        }
        return out;
    }

    private sealed interface Message {
        record Change(CommittedChange change) implements Message {
        }

        record Ack(long clientSeq, long rev) implements Message {
        }

        record Nack(long clientSeq) implements Message {
        }
    }

    private record InTransit(Submission submission, int epoch) {
    }

    /** 클라이언트 하나. 모든 호출을 이 클래스를 거쳐서 하고, 호출마다 기록을 남긴다. */
    private static final class Peer {
        final ClientSession session;
        final RandomOps generator;
        final Deque<InTransit> toServer = new ArrayDeque<>();
        final Deque<Message> toClient = new ArrayDeque<>();
        final ArrayNode events = VectorJson.array();
        Document lastView;
        boolean connected = true;
        int epoch;

        Peer(ClientSession session, RandomOps generator) {
            this.session = session;
            this.generator = generator;
            this.lastView = session.view();
        }

        void edit(List<Op> ops) {
            session.edit(ops);
            ObjectNode event = event("edit");
            event.set("ops", VectorJson.ops(ops));
            state(event);
        }

        /** 보낼 것이 전혀 없을 때의 poll 은 상태를 바꾸지 않으므로 적지 않는다. 기록이 너무 커지는 것을 막는다. */
        Optional<Submission> poll() {
            boolean hadPending = session.hasPending();
            Optional<Submission> submission = session.poll();
            if (!hadPending) {
                return submission;
            }
            ObjectNode event = event("poll");
            submission(event, submission);
            state(event);
            return submission;
        }

        void onChange(CommittedChange change) {
            session.onChange(change);
            ObjectNode event = event("change");
            event.set("change", committed(change));
            state(event);
        }

        void onAck(long clientSeq, long rev) {
            session.onAck(clientSeq, rev);
            ObjectNode event = event("ack");
            event.put("clientSeq", clientSeq);
            event.put("rev", rev);
            state(event);
        }

        void onNack(long clientSeq) {
            session.onNack(clientSeq);
            ObjectNode event = event("nack");
            event.put("clientSeq", clientSeq);
            state(event);
        }

        Optional<Submission> resync(List<CommittedChange> missed) {
            Optional<Submission> submission = session.resync(missed);
            ObjectNode event = event("resync");
            ArrayNode changes = event.putArray("missed");
            missed.forEach(change -> changes.add(committed(change)));
            submission(event, submission);
            state(event);
            return submission;
        }

        private ObjectNode event(String kind) {
            ObjectNode event = events.addObject();
            event.put("call", kind);
            return event;
        }

        private static void submission(ObjectNode event, Optional<Submission> submission) {
            if (submission.isEmpty()) {
                event.putNull("submission");
                return;
            }
            Submission s = submission.get();
            ObjectNode node = event.putObject("submission");
            node.put("clientId", s.clientId());
            node.put("clientSeq", s.clientSeq());
            node.put("baseRev", s.baseRev());
            node.set("ops", VectorJson.ops(s.ops()));
        }

        private void state(ObjectNode event) {
            ObjectNode state = event.putObject("state");
            state.put("confirmedRev", session.confirmedRev());
            state.put("hasPending", session.hasPending());
            if (!session.view().equals(lastView)) {
                lastView = session.view();
                state.set("view", VectorJson.document(lastView));
            }
        }

        private static ObjectNode committed(CommittedChange change) {
            ObjectNode node = VectorJson.object();
            node.put("rev", change.rev());
            node.put("clientId", change.clientId());
            node.put("clientSeq", change.clientSeq());
            node.set("ops", VectorJson.ops(change.ops()));
            return node;
        }
    }

    private static final class Simulation {
        private final long seed;
        private final Random random;
        private final Document start;
        private final ServerDocument server;
        private final List<Peer> peers = new ArrayList<>();
        private final Set<String> acceptedKeys = new HashSet<>();

        Simulation(long seed) {
            this.seed = seed;
            this.random = new Random(seed);
            this.start = new RandomOps(random, "s").randomDocument(1 + random.nextInt(5));
            this.server = new ServerDocument(start, 0);
            int clients = 2 + random.nextInt(4);
            for (int i = 0; i < clients; i++) {
                String id = "c" + i;
                peers.add(new Peer(new ClientSession(id, start, 0), new RandomOps(random, id + "-")));
            }
        }

        ObjectNode run() {
            int steps = 40 + random.nextInt(160);
            for (int i = 0; i < steps; i++) {
                step();
            }
            drain();
            verify();

            ObjectNode node = VectorJson.object();
            node.put("seed", seed);
            node.set("start", VectorJson.document(start));
            node.put("startRev", 0);
            ArrayNode clients = node.putArray("clients");
            for (Peer peer : peers) {
                ObjectNode client = clients.addObject();
                client.put("clientId", peer.session.clientId());
                client.set("events", peer.events);
                ObjectNode end = client.putObject("end");
                end.set("confirmed", VectorJson.document(peer.session.confirmed()));
                end.set("view", VectorJson.document(peer.session.view()));
            }
            return node;
        }

        private void step() {
            Peer peer = peers.get(random.nextInt(peers.size()));
            int roll = random.nextInt(100);
            if (roll < 35) {
                peer.edit(peer.generator.randomChange(peer.session.view(), 3));
                send(peer);
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

        private void send(Peer peer) {
            if (peer.connected) {
                peer.poll().ifPresent(s -> peer.toServer.add(new InTransit(s, peer.epoch)));
            }
        }

        private void serverReceives(Peer sender) {
            InTransit transit = sender.toServer.poll();
            if (transit == null) {
                return;
            }
            Submission submission = transit.submission();
            boolean sameConnection = sender.connected && transit.epoch() == sender.epoch;
            String key = submission.clientId() + "#" + submission.clientSeq();
            // 서버 자기 검사에 걸린 경우. 이미 확정된 묶음의 재전송은 중복 검사가 먼저라서 여기 오지 않는다.
            if (!acceptedKeys.contains(key) && random.nextInt(30) == 0) {
                if (sameConnection) {
                    sender.toClient.add(new Message.Nack(submission.clientSeq()));
                }
                return;
            }
            switch (server.submit(submission)) {
                case SubmitResult.Accepted accepted -> {
                    acceptedKeys.add(key);
                    for (Peer peer : peers) {
                        if (!peer.connected) {
                            continue;
                        }
                        if (peer == sender && sameConnection) {
                            peer.toClient.add(new Message.Ack(submission.clientSeq(), accepted.change().rev()));
                        } else {
                            peer.toClient.add(new Message.Change(accepted.change()));
                        }
                    }
                }
                case SubmitResult.Duplicate duplicate -> {
                    if (sameConnection) {
                        sender.toClient.add(new Message.Ack(submission.clientSeq(), duplicate.rev()));
                    }
                }
                case SubmitResult.Rejected rejected -> {
                    if (sameConnection) {
                        sender.toClient.add(new Message.Nack(submission.clientSeq()));
                    }
                }
                case SubmitResult.Stale stale ->
                        throw new AssertionError("기록 창 없이 만든 서버는 Stale 을 돌려주지 않는다");
            }
        }

        private void clientReceives(Peer peer) {
            Message message = peer.toClient.poll();
            if (message == null) {
                return;
            }
            switch (message) {
                case Message.Change change -> peer.onChange(change.change());
                case Message.Ack ack -> peer.onAck(ack.clientSeq(), ack.rev());
                case Message.Nack nack -> peer.onNack(nack.clientSeq());
            }
            send(peer);
        }

        private void disconnect(Peer peer) {
            if (!peer.connected) {
                return;
            }
            peer.connected = false;
            peer.toClient.clear();
            if (random.nextBoolean()) {
                peer.toServer.clear();
            }
        }

        private void reconnect(Peer peer) {
            if (peer.connected) {
                return;
            }
            if (random.nextBoolean()) {
                while (!peer.toServer.isEmpty()) {
                    serverReceives(peer);
                }
            }
            peer.connected = true;
            peer.epoch++;
            peer.resync(server.changesSince(peer.session.confirmedRev()))
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
                    throw new IllegalStateException("시드 " + seed + ": 메시지가 끝나지 않는다");
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

        /** 벡터로 내보내기 전에 시뮬레이션이 실제로 수렴했는지 확인한다. 수렴하지 않은 기록은 기준이 될 수 없다. */
        private void verify() {
            for (Peer peer : peers) {
                ClientSession session = peer.session;
                if (session.hasPending()
                        || session.confirmedRev() != server.rev()
                        || !session.confirmed().equals(server.document())
                        || !session.view().equals(server.document())) {
                    throw new IllegalStateException("시드 " + seed + ": 클라이언트 " + session.clientId() + " 가 수렴하지 않았다");
                }
            }
        }
    }
}
