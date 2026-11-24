package com.copage.collab.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copage.collab.app.CloseCodes;
import com.copage.collab.app.TicketService;
import com.copage.collab.domain.PageRole;
import com.copage.collab.in.CollabWebSocketHandler;
import com.copage.support.TestInfra;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * 받는 쪽이 느린 클라이언트 (docs/11-TEST-PLAN.md 13.2, docs/CHAOS.md). 한 연결이 읽기를 멈춰도 같은 문서의 다른 사람은
 * ack 와 남의 편집을 늦지 않게 받고, 멈춘 연결만 서버가 버린다(4011).
 *
 * <p>멈춘 연결의 TCP 버퍼가 차도록 큰 편집(60KB)을 넣고 지우기를 되풀이해 수 MB 를 흘린다. 서버의 보낼 대기열 한도는
 * 시험에서 256KB, 쓰기 시간 한도는 2초로 줄였다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"copage.collab.send-buffer-bytes=262144", "copage.collab.send-time-limit=2s"})
class SlowClientTest extends TestInfra {

    private static final int ROUNDS = 40;
    private static final String BIG = "a".repeat(60_000);

    @LocalServerPort
    int port;

    @Autowired
    TicketService tickets;

    @Autowired
    CollabWebSocketHandler handler;

    private URI uri(UUID docId) {
        String ticket = tickets.issue(UUID.randomUUID(), docId, PageRole.EDIT, "테스터");
        return URI.create("ws://localhost:" + port + "/ws/docs/" + docId + "?ticket=" + ticket + "&v=1");
    }

    @Test
    void 읽기를_멈춘_연결만_끊기고_다른_사람의_ack_와_반영은_늦어지지_않는다() throws Exception {
        UUID docId = UUID.randomUUID();
        RawClient writer = new RawClient(false);
        RawClient reader = new RawClient(false);
        RawClient slow = new RawClient(true);
        long rev = writer.connect(uri(docId), "writer").get("rev").longValue();
        reader.connect(uri(docId), "reader");
        slow.connect(uri(docId), "slow");

        long ackMax = 0;
        long remoteMax = 0;
        int seq = 0;
        for (int round = 0; round < ROUNDS * 2; round++) {
            // 넣고 지우기를 되풀이해 문서는 커지지 않고 흐르는 양만 늘린다.
            String delta = round % 2 == 0 ? "[{\"insert\":\"" + BIG + "\"}]" : "[{\"delete\":" + BIG.length() + "}]";
            long sentAt = System.nanoTime();
            writer.send("{\"t\":\"change\",\"clientSeq\":" + (++seq) + ",\"baseRev\":" + rev
                    + ",\"ops\":[{\"op\":\"text\",\"blockId\":\"" + docId + "\",\"delta\":" + delta + "}]}");
            RawClient.Received ack = writer.await("ack", 10_000);
            rev = ack.message().get("rev").longValue();
            RawClient.Received remote = reader.await("remote", 10_000);
            assertEquals(rev, remote.message().get("rev").longValue());
            ackMax = Math.max(ackMax, (ack.atNanos() - sentAt) / 1_000_000);
            remoteMax = Math.max(remoteMax, (remote.atNanos() - sentAt) / 1_000_000);
            // 연결마다 초당 30개 한도 아래로 보낸다.
            Thread.sleep(40);
        }

        // 쓰기 시간 한도(2초)만큼 막혀 있었다면 이보다 훨씬 늦었다.
        assertTrue(ackMax < 1_000, "ack 가 늦어졌다: " + ackMax + "ms");
        assertTrue(remoteMax < 1_000, "남의 편집 반영이 늦어졌다: " + remoteMax + "ms");

        // 서버는 멈춘 연결을 이미 버렸다(읽기를 다시 시작하기 전에).
        long deadline = System.currentTimeMillis() + 10_000;
        while (handler.connectionCount() > 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(2, handler.connectionCount(), "멈춘 연결이 남아 있다");

        // 멈춘 연결은 닫혀 있다. 받는 쪽이 막혀 서버의 닫기 프레임(4011)이 닿지 못하면 비정상 종료(1006)로 보인다. 둘 다
        // 클라이언트가 다시 붙어 동기화하는 종료다. 그 사이 쌓인 메시지는 버려졌다.
        slow.resume();
        int code = slow.closed.get(15, TimeUnit.SECONDS);
        assertTrue(code == CloseCodes.SLOW || code == 1006, "닫힌 코드: " + code);
        long slowRemotes = slow.inbox.stream().filter(r -> "remote".equals(r.message().get("t").stringValue())).count();
        assertTrue(slowRemotes < ROUNDS * 2, "멈춘 연결이 모두 받았다면 끊긴 것이 아니다: " + slowRemotes);
        System.out.printf("느린 클라이언트 실험: ack 최대 %dms, 반영 최대 %dms, 멈춘 연결이 받은 remote %d/%d, 닫힌 코드 %d%n",
                ackMax, remoteMax, slowRemotes, ROUNDS * 2, code);
    }
}
