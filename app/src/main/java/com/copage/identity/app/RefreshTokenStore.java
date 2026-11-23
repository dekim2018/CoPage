package com.copage.identity.app;

import java.time.Duration;
import java.util.UUID;

/**
 * Refresh 토큰 저장소 포트. 토큰 자체가 아니라 해시만 둔다 (docs/07-SECURITY.md 9.1).
 *
 * <p>쓴 토큰은 바로 지우지 않고 "썼음" 과 쓴 시각을 남겨 둔다. 그래야 같은 토큰이 다시 왔을 때 탈취로 알아볼 수 있다. 다만
 * 쓴 직후 짧은 유예 시간 안에 다시 오면 같은 사용자의 동시 재발급(탭 두 개, 재시도)으로 본다.
 */
public interface RefreshTokenStore {

    void save(String tokenHash, UUID userId, Duration ttl);

    /** 토큰을 쓴 것으로 표시하고 그 전 상태를 돌려준다. 표시와 확인은 한 번에 일어난다. */
    Use use(String tokenHash);

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
    }
}
