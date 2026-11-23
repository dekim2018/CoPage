package com.copage.identity.app;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh 토큰 저장소 포트. 토큰 자체가 아니라 해시만 둔다 (docs/07-SECURITY.md 9.1).
 *
 * <p>쓴 토큰은 바로 지우지 않고 "썼음" 과 쓴 시각을 남겨 둔다. 그래야 같은 토큰이 다시 왔을 때 탈취로 알아볼 수 있다. 다만
 * 쓴 직후 짧은 유예 시간 안에 다시 오면 같은 사용자의 동시 재발급(탭 두 개, 재시도)으로 본다.
 */
public interface RefreshTokenStore {

    void save(String tokenHash, UUID userId, Duration ttl);

    /** 토큰의 주인. 상태를 바꾸지 않는다. 없거나 만료되었으면 비어 있다. */
    Optional<UUID> owner(String tokenHash);

    /**
     * 토큰을 쓴 것으로 표시하고 그 전 상태를 돌려준다. 처음 쓰는 것이면 같은 주인의 새 토큰(nextHash)을 함께 저장한다. 확인과
     * 표시와 저장은 한 번에 일어난다.
     *
     * <p>저장소가 답하지 않아 호출이 실패하면 아무것도 바뀌지 않아야 한다. 그렇지 않으면 저장소가 밀린 명령을 나중에 처리하면서 옛
     * 토큰을 써 버리고, 새 토큰은 끝내 클라이언트에 닿지 않아 세션을 잃는다 (docs/07-SECURITY.md 9.7).
     */
    Use rotate(String tokenHash, String nextHash, Duration ttl);

    /** 사용자의 Refresh 토큰을 모두 지운다. */
    void revokeAll(UUID userId);

    void delete(String tokenHash);

    sealed interface Use {
        /** 처음 쓴다. */
        record Active(UUID userId) implements Use {
        }

        /** 쓴 직후 유예 시간 안에 다시 왔다. 동시 재발급으로 본다. */
        record RecentlyUsed(UUID userId) implements Use {
        }

        /** 유예 시간이 지난 뒤 이미 쓴 토큰이 다시 왔다. 탈취로 본다. */
        record Reused(UUID userId) implements Use {
        }

        /** 없거나 만료되었다. */
        record Unknown() implements Use {
        }

        /** 저장소가 기한이 지난 뒤에 처리해서 아무것도 바꾸지 않았다. 저장소 구현 안에서만 쓰고 밖으로 돌려주지 않는다. */
        record Late() implements Use {
        }
    }
}
