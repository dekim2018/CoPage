package com.copage.comment.app;

import com.copage.comment.app.CommentException.Kind;
import com.copage.comment.domain.Comment;
import com.copage.comment.domain.CommentThread;
import com.copage.notification.app.NotificationService;
import com.copage.notification.domain.Notification;
import com.copage.permission.app.PagePermissions;
import com.copage.permission.app.PagePermissions.Access;
import com.copage.permission.domain.PageLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 댓글 (docs/08-FEATURES.md 10.2, 10.3, C-1~C-4, D-46). 보기는 VIEW, 쓰기와 해결은 COMMENT 부터.
 *
 * <p>스레드를 만든 뒤 화면이 본문의 글자에 comment 서식을 붙인다(편집 연산). 쓰고 나면 그 문서를 연 사람들에게 알리고, 멘션한
 * 사람과 답글이 달린 스레드의 참여자에게 알림을 보낸다. 페이지를 볼 수 없는 사람은 멘션해도 알리지 않는다.
 */
public final class CommentService {

    static final int MAX_BODY = 4_000;
    static final int MAX_QUOTE = 1_000;
    /** 한 댓글에서 멘션할 수 있는 사람 수. 사람마다 권한 확인과 알림 한 줄이 든다. */
    static final int MAX_MENTIONS = 50;

    private final CommentRepository repository;
    private final PagePermissions permissions;
    private final NotificationService notifications;
    /** (문서, 스레드) 가 바뀌었다고 연결들에 알린다. */
    private final BiConsumer<UUID, UUID> announce;
    private final Clock clock;

    public CommentService(
            CommentRepository repository,
            PagePermissions permissions,
            NotificationService notifications,
            BiConsumer<UUID, UUID> announce,
            Clock clock) {
        this.repository = repository;
        this.permissions = permissions;
        this.notifications = notifications;
        this.announce = announce;
        this.clock = clock;
    }

    public List<CommentThread> threads(UUID userId, UUID docId) {
        require(userId, docId, PageLevel.VIEW);
        return repository.threads(docId);
    }

    /** @param mentions 본문에서 멘션한 사람 */
    public CommentThread create(UUID userId, UUID docId, String quote, String body, Collection<UUID> mentions) {
        require(userId, docId, PageLevel.COMMENT);
        String text = body(body);
        checkMentions(mentions);
        if (quote == null || quote.isBlank()) {
            throw new CommentException(Kind.BAD_REQUEST, "댓글을 달 글자를 골라야 한다");
        }
        String trimmedQuote = quote.length() > MAX_QUOTE ? quote.substring(0, MAX_QUOTE) : quote;
        Instant now = now();
        UUID threadId = UUID.randomUUID();
        Comment first = new Comment(UUID.randomUUID(), threadId, userId, null, text, now);
        CommentThread thread = new CommentThread(threadId, docId, trimmedQuote, userId, now, null, null, List.of(first));
        repository.createThread(thread, first);
        notifyMentions(docId, userId, first, mentions);
        announce.accept(docId, threadId);
        return thread;
    }

    public Comment reply(UUID userId, UUID threadId, String body, Collection<UUID> mentions) {
        UUID docId = docOf(threadId);
        require(userId, docId, PageLevel.COMMENT);
        checkMentions(mentions);
        Comment comment = new Comment(UUID.randomUUID(), threadId, userId, null, body(body), now());
        Set<UUID> participants = repository.participants(threadId);
        repository.addComment(comment);
        for (UUID participant : participants) {
            if (!mentions.contains(participant) && canView(participant, docId)) {
                notifications.notify(participant, Notification.Kind.REPLY, docId, userId, comment.body(),
                        "reply:" + comment.id() + ":" + participant);
            }
        }
        notifyMentions(docId, userId, comment, mentions);
        announce.accept(docId, threadId);
        return comment;
    }

    public void resolve(UUID userId, UUID threadId, boolean resolved) {
        UUID docId = docOf(threadId);
        require(userId, docId, PageLevel.COMMENT);
        repository.setResolved(threadId, resolved ? now() : null, resolved ? userId : null);
        announce.accept(docId, threadId);
    }

    /**
     * 내 댓글을 고친다 (D-49). 쓴 사람만, 그리고 지금도 그 문서에 COMMENT 이상이어야 한다. 새로 멘션한 사람에게는 알린다(이미 알린
     * 사람에게는 다시 가지 않는다).
     *
     * @throws CommentException NOT_FOUND 없거나 지운 댓글, FORBIDDEN 남의 댓글이거나 등급이 모자람
     */
    public Comment edit(UUID userId, UUID commentId, String body, Collection<UUID> mentions) {
        CommentRepository.CommentRef ref = live(commentId);
        require(userId, ref.docId(), PageLevel.COMMENT);
        if (!ref.authorId().equals(userId)) {
            throw new CommentException(Kind.FORBIDDEN, "내 댓글만 고친다");
        }
        checkMentions(mentions);
        String text = body(body);
        Instant now = now();
        if (!repository.editComment(commentId, text, now)) {
            throw new CommentException(Kind.NOT_FOUND, "댓글이 없다");
        }
        Comment edited = new Comment(commentId, ref.threadId(), userId, null, text, now, now, false);
        notifyMentions(ref.docId(), userId, edited, mentions);
        announce.accept(ref.docId(), ref.threadId());
        return edited;
    }

    /**
     * 댓글을 지운다 (D-49). 쓴 사람(COMMENT 이상)이나 그 문서의 FULL 이 지운다. 글은 비우고, 그 댓글을 담은 알림(멘션, 답글)도
     * 지운다. 스레드에 남은 댓글이 없으면 스레드도 지운다. 그때 화면이 본문의 댓글 서식을 걷는다.
     *
     * @return 스레드까지 지웠으면 true
     */
    public boolean delete(UUID userId, UUID commentId) {
        CommentRepository.CommentRef ref = live(commentId);
        PageLevel level = require(userId, ref.docId(), PageLevel.COMMENT);
        if (!ref.authorId().equals(userId) && !level.atLeast(PageLevel.FULL)) {
            throw new CommentException(Kind.FORBIDDEN, "내 댓글이나 전체 권한이 있는 페이지의 댓글만 지운다");
        }
        boolean threadGone = repository.deleteComment(commentId, now());
        notifications.forget("reply:" + commentId + ":");
        notifications.forget("comment-mention:" + commentId + ":");
        announce.accept(ref.docId(), ref.threadId());
        return threadGone;
    }

    private CommentRepository.CommentRef live(UUID commentId) {
        return repository.findComment(commentId)
                .filter(ref -> !ref.deleted())
                .orElseThrow(() -> new CommentException(Kind.NOT_FOUND, "댓글이 없다"));
    }

    private void notifyMentions(UUID docId, UUID actorId, Comment comment, Collection<UUID> mentions) {
        for (UUID mentioned : new LinkedHashSet<>(mentions)) {
            if (canView(mentioned, docId)) {
                notifications.notify(mentioned, Notification.Kind.COMMENT_MENTION, docId, actorId, comment.body(),
                        "comment-mention:" + comment.id() + ":" + mentioned);
            }
        }
    }

    private static void checkMentions(Collection<UUID> mentions) {
        if (mentions.size() > MAX_MENTIONS) {
            throw new CommentException(Kind.BAD_REQUEST, "한 댓글에서 " + MAX_MENTIONS + " 명까지 멘션한다");
        }
    }

    private boolean canView(UUID userId, UUID docId) {
        return permissions.access(userId, docId) instanceof Access.Granted;
    }

    private UUID docOf(UUID threadId) {
        return repository.docOf(threadId).orElseThrow(() -> new CommentException(Kind.NOT_FOUND, "댓글이 없다"));
    }

    /** @return 그 문서에 대한 내 등급 */
    private PageLevel require(UUID userId, UUID docId, PageLevel level) {
        return switch (permissions.access(userId, docId)) {
            case Access.NotFound notFound -> throw new CommentException(Kind.NOT_FOUND, "페이지가 없다");
            case Access.Denied denied -> throw new CommentException(Kind.FORBIDDEN, "이 페이지에 권한이 없다");
            case Access.Granted granted -> {
                if (!granted.level().atLeast(level)) {
                    throw new CommentException(Kind.FORBIDDEN, "댓글 권한이 없다");
                }
                yield granted.level();
            }
        };
    }

    private static String body(String body) {
        if (body == null || body.isBlank()) {
            throw new CommentException(Kind.BAD_REQUEST, "댓글이 비었다");
        }
        String trimmed = body.strip();
        if (trimmed.length() > MAX_BODY) {
            throw new CommentException(Kind.BAD_REQUEST, "댓글은 " + MAX_BODY + " 자까지다");
        }
        return trimmed;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
