package com.copage.comment.in;

import com.copage.comment.app.CommentService;
import com.copage.comment.domain.Comment;
import com.copage.comment.domain.CommentThread;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 댓글 (docs/09-API.md 11.4, C-1~C-4, D-46). 보기는 VIEW, 쓰기와 해결은 COMMENT 부터. */
@RestController
class CommentController {

    /** @param deleted 지운 댓글. 글은 비어 있다 */
    record CommentView(UUID id, UUID authorId, String authorName, String body, Instant createdAt, Instant editedAt,
            boolean deleted) {
        static CommentView of(Comment c) {
            return new CommentView(c.id(), c.authorId(), c.authorName(), c.body(), c.createdAt(), c.editedAt(),
                    c.deleted());
        }
    }

    record EditRequest(String body, List<UUID> mentions) {
    }

    /** @param threadDeleted 남은 댓글이 없어 스레드도 지웠다. 화면은 본문의 댓글 서식을 걷는다 */
    record DeleteResponse(boolean threadDeleted) {
    }

    /** @param orphaned 본문에서 원문이 다 지워졌다 (D-50) */
    record ThreadView(UUID id, String quote, Instant createdAt, boolean resolved, String resolvedByName,
            List<CommentView> comments, boolean orphaned) {
        static ThreadView of(CommentThread t) {
            return new ThreadView(t.id(), t.quote(), t.createdAt(), t.resolvedAt() != null, t.resolvedByName(),
                    t.comments().stream().map(CommentView::of).toList(), t.orphaned());
        }
    }

    /** @param mentions 본문에서 멘션한 사람의 ID */
    record CreateRequest(String quote, String body, List<UUID> mentions) {
    }

    record ReplyRequest(String body, List<UUID> mentions) {
    }

    record ResolveRequest(boolean resolved) {
    }

    private final CommentService comments;

    CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/api/docs/{docId}/comments")
    List<ThreadView> threads(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId) {
        return comments.threads(userId(jwt), docId).stream().map(ThreadView::of).toList();
    }

    /** 스레드를 만든다. 화면은 돌려받은 ID 로 본문의 그 글자에 comment 서식을 붙인다. */
    @PostMapping("/api/docs/{docId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    ThreadView create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID docId, @RequestBody CreateRequest request) {
        return ThreadView.of(comments.create(userId(jwt), docId, request.quote(), request.body(), mentions(request.mentions())));
    }

    @PostMapping("/api/comments/{threadId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    CommentView reply(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID threadId, @RequestBody ReplyRequest request) {
        return CommentView.of(comments.reply(userId(jwt), threadId, request.body(), mentions(request.mentions())));
    }

    @PutMapping("/api/comments/{threadId}/resolved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID threadId, @RequestBody ResolveRequest request) {
        comments.resolve(userId(jwt), threadId, request.resolved());
    }

    /** 내 댓글 고치기 (D-49). 여기의 ID 는 댓글 ID 다(답글과 해결의 ID 는 스레드 ID). */
    @PatchMapping("/api/comments/{commentId}")
    CommentView edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId, @RequestBody EditRequest request) {
        return CommentView.of(comments.edit(userId(jwt), commentId, request.body(), mentions(request.mentions())));
    }

    /** 댓글 지우기 (D-49). 쓴 사람이나 그 페이지의 FULL. */
    @DeleteMapping("/api/comments/{commentId}")
    DeleteResponse delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId) {
        return new DeleteResponse(comments.delete(userId(jwt), commentId));
    }

    private static List<UUID> mentions(List<UUID> mentions) {
        return mentions == null ? List.of() : mentions;
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
