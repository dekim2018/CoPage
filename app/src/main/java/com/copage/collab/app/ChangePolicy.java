package com.copage.collab.app;

import com.copage.collab.domain.PageRole;
import com.copage.ot.model.Block;
import com.copage.ot.model.Document;
import com.copage.ot.model.InvalidOperationException;
import com.copage.ot.op.Op;
import com.copage.ot.op.Op.InsertBlock;
import com.copage.ot.op.Op.SetProps;
import com.copage.ot.op.Op.SplitBlock;
import com.copage.ot.op.Op.TextOp;
import com.copage.ot.text.Component;
import com.copage.ot.text.Component.Insert;
import com.copage.ot.text.Component.Retain;
import com.copage.ot.text.Segment;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 협업 서버가 묶음을 받을 때 거는 규칙. 어기면 묶음을 거부(nack)한다.
 *
 * <ul>
 *   <li>연산 단위 인가 (docs/07-SECURITY.md 9.5): VIEW 는 편집 불가, COMMENT 는 comment 서식만 바꾸는 retain 만 가능
 *   <li>입력 검사 (9.6): 허용한 블록 종류와 서식 이름, 링크 주소의 형식
 *   <li>문서 크기 한도 (docs/05-PROTOCOL.md 7.7): 변환하고 적용한 결과를 본다
 * </ul>
 *
 * <p>없는 블록이나 길이를 넘는 위치는 여기서 보지 않는다. 적용할 때 ot-core 가 거부한다.
 */
public final class ChangePolicy {

    /** 블록 종류 10종 (docs/01-PLAN.md E-1). 루트 블록의 page 는 연산으로 만들 수 없다. */
    public static final Set<String> BLOCK_TYPES = Set.of(
            "paragraph", "heading", "bulleted", "numbered", "todo", "toggle", "quote", "callout", "code", "divider");

    /** 글자 서식 (E-2 의 6종과 댓글, 멘션). */
    public static final Set<String> MARKS =
            Set.of("bold", "italic", "underline", "strike", "code", "link", "comment", "mention");

    private static final String COMMENT_MARK = "comment";
    private static final String LINK_MARK = "link";
    private static final List<String> LINK_SCHEMES = List.of("http://", "https://", "mailto:");

    private final int maxBlocks;
    private final long maxTextLength;

    /**
     * @param maxBlocks 문서 하나의 블록 수 한도. 삭제 표시된 블록도 센다
     * @param maxTextLength 문서 하나의 글자 수 한도. UTF-16 코드 단위로 센다
     */
    public ChangePolicy(int maxBlocks, long maxTextLength) {
        this.maxBlocks = maxBlocks;
        this.maxTextLength = maxTextLength;
    }

    /** 변환하기 전, 클라이언트가 보낸 그대로의 묶음을 검사한다. 거부 사유가 있으면 돌려준다. */
    public Optional<String> check(List<Op> ops, PageRole role) {
        for (Op op : ops) {
            Optional<String> reason = checkRole(op, role).or(() -> checkInput(op));
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    /** 받아들이기 직전의 결과 문서를 검사한다. ServerDocument.submit 의 validator 로 쓴다. */
    public void checkSize(Document document) {
        if (document.blockCount() > maxBlocks) {
            throw new InvalidOperationException("문서의 블록 수가 한도 " + maxBlocks + " 개를 넘는다");
        }
        long length = 0;
        for (Block block : document.blocks()) {
            length += block.text().length();
        }
        if (length > maxTextLength) {
            throw new InvalidOperationException("문서의 글자 수가 한도 " + maxTextLength + " 를 넘는다");
        }
    }

    private static Optional<String> checkRole(Op op, PageRole role) {
        if (role.atLeast(PageRole.EDIT)) {
            return Optional.empty();
        }
        if (role == PageRole.COMMENT && op instanceof TextOp text && onlyCommentRetains(text)) {
            return Optional.empty();
        }
        return Optional.of("권한 " + role + " 으로는 이 연산을 할 수 없다");
    }

    private static boolean onlyCommentRetains(TextOp text) {
        for (Component component : text.delta().components()) {
            if (!(component instanceof Retain retain)) {
                return false;
            }
            if (!retain.attrs().isEmpty() && !retain.attrs().keySet().equals(Set.of(COMMENT_MARK))) {
                return false;
            }
        }
        return true;
    }

    private static Optional<String> checkInput(Op op) {
        return switch (op) {
            case TextOp text -> {
                for (Component component : text.delta().components()) {
                    Map<String, Object> attrs = switch (component) {
                        case Retain retain -> retain.attrs();
                        case Insert insert -> insert.attrs();
                        case Component.Delete delete -> Map.of();
                    };
                    Optional<String> reason = checkMarks(attrs);
                    if (reason.isPresent()) {
                        yield reason;
                    }
                }
                yield Optional.empty();
            }
            case SplitBlock split -> checkType(split.type());
            case InsertBlock insert -> {
                Optional<String> reason = checkType(insert.type());
                for (Segment segment : insert.text().segments()) {
                    reason = reason.or(() -> checkMarks(segment.attrs()));
                }
                yield reason;
            }
            case SetProps setProps when Op.TYPE_KEY.equals(setProps.key()) ->
                    setProps.value() instanceof String type ? checkType(type) : Optional.of("블록 종류가 문자열이 아니다");
            default -> Optional.empty();
        };
    }

    private static Optional<String> checkType(String type) {
        return BLOCK_TYPES.contains(type) ? Optional.empty() : Optional.of("허용하지 않는 블록 종류: " + type);
    }

    private static Optional<String> checkMarks(Map<String, Object> attrs) {
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            if (!MARKS.contains(entry.getKey())) {
                return Optional.of("허용하지 않는 서식: " + entry.getKey());
            }
            if (LINK_MARK.equals(entry.getKey()) && entry.getValue() != null && !isAllowedLink(entry.getValue())) {
                return Optional.of("링크 주소는 http, https, mailto 만 쓸 수 있다");
            }
        }
        return Optional.empty();
    }

    private static boolean isAllowedLink(Object value) {
        if (!(value instanceof String url)) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return LINK_SCHEMES.stream().anyMatch(lower::startsWith);
    }
}
