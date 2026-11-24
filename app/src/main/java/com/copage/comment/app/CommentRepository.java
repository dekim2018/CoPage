package com.copage.comment.app;

import com.copage.comment.domain.Comment;
import com.copage.comment.domain.CommentThread;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 댓글 저장소 포트. */
public interface CommentRepository {

    /** 스레드와 첫 댓글을 한 트랜잭션에 넣는다. */
    void createThread(CommentThread thread, Comment first);

    void addComment(Comment comment);

    /** 문서의 스레드와 댓글. 오래된 스레드부터, 스레드 안은 오래된 댓글부터. */
    List<CommentThread> threads(UUID docId);

    /** 스레드가 달린 문서. 없으면 비어 있다. */
    Optional<UUID> docOf(UUID threadId);

    /** 스레드에 글을 쓴 사람들. */
    Set<UUID> participants(UUID threadId);

    /** 댓글 하나가 어느 스레드와 문서에 있고 누가 썼는지 (D-49). */
    record CommentRef(UUID commentId, UUID threadId, UUID docId, UUID authorId, boolean deleted) {
    }

    Optional<CommentRef> findComment(UUID commentId);

    /** 지우지 않은 댓글의 글을 바꾼다. 바꿨으면 true. */
    boolean editComment(UUID commentId, String body, Instant editedAt);

    /**
     * 댓글을 지운다(글을 비우고 지운 시각을 남긴다). 그 스레드에 남은 댓글이 없으면 스레드도 지운다. 한 트랜잭션이다.
     *
     * @return 스레드까지 지웠으면 true
     */
    boolean deleteComment(UUID commentId, Instant deletedAt);

    /** resolvedAt 이 null 이면 다시 연다. */
    boolean setResolved(UUID threadId, Instant resolvedAt, UUID resolvedBy);
}
