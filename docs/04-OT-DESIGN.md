# 6. 문서 모델과 OT 설계

블록은 ID로 가리키고 블록 안의 글자만 위치(offset)로 가리킨다. 그래서 위치를 다시 계산하는 변환은 글자를 다루는 세 가지 연산(텍스트 편집, 블록 나누기, 블록 합치기)에만 필요하고, 나머지 구조 연산은 서버가 정한 순서대로 적용하면서 검사만 한다. 이 구분이 블록 기반 OT를 혼자 구현할 수 있는 크기로 줄여 준다.

## 6.1 문서 모델

페이지 하나는 블록 트리 하나다. 루트 블록이 제목을 갖고, 그 아래에 본문 블록이 순서대로 달린다.

```json
{
  "rev": 122,
  "root": "b_root",
  "blocks": {
    "b_root": { "type": "page", "text": [{ "insert": "회의록" }], "children": ["b_01", "b_02"] },
    "b_01": {
      "type": "heading", "props": { "level": 2 }, "pos": "a0",
      "text": [{ "insert": "결정 사항" }]
    },
    "b_02": {
      "type": "todo", "props": { "checked": false }, "pos": "a1",
      "text": [
        { "insert": "배포 일정 " },
        { "insert": "확정", "attrs": { "bold": true, "comment": ["c_91"] } }
      ],
      "children": []
    }
  }
}
```

| 필드 | 설명 |
| --- | --- |
| 블록 ID | 클라이언트가 만드는 UUIDv7. 서버 왕복 없이 블록을 만들 수 있고 오프라인에서도 충돌하지 않는다 |
| `type`, `props` | 블록 종류와 종류별 속성(제목 단계, 체크 여부, 코드 언어) |
| `text` | 글자와 서식의 나열. 같은 서식이 이어지는 구간을 한 조각으로 묶는다 |
| `pos` | 형제 사이 순서를 정하는 문자열 키(분수 인덱스, 62진 소수, 0 으로 끝나지 않음). 두 키 사이에는 항상 새 키를 만들 수 있어 다른 블록의 순번을 고치지 않고 끼워 넣는다. 맨 뒤와 맨 앞 추가는 첫 글자 아래에서 세어 키가 로그로만 자란다(D-24). 서버는 모양이 틀리거나 256 글자를 넘는 키를 거부한다 |
| `deleted` | 삭제 표시. 블록을 지우면 바로 없애지 않고 표시만 한다 (6.6 참고) |

블록 종류(`type`)는 10종이다. `paragraph`, `heading`(`props.level` 1~3), `bulleted`, `numbered`, `todo`, `toggle`, `quote`, `callout`, `code`, `divider`. 루트 블록만 `page` 다. 글자 서식 이름은 `bold`, `italic`, `underline`, `strike`, `code`, `link`, `comment`, `mention` 이다. 협업 서버는 이 밖의 종류와 서식을 거부한다.

글자 위치는 UTF-16 코드 단위로 센다. Java의 `String` 과 JavaScript의 문자열이 둘 다 UTF-16이라 서버와 브라우저가 같은 숫자를 쓴다. 이모지처럼 코드 단위 2개를 쓰는 글자의 가운데를 가르는 연산은 서버가 거부한다.

## 6.2 연산 7종

| 연산 | 내용 | 주소 방식 | 충돌 처리 |
| --- | --- | --- | --- |
| `text(blockId, delta)` | 블록 안 글자 삽입, 삭제, 서식 변경 | 위치 | 변환 |
| `split(blockId, offset, newId, parentId, pos, type, props)` | offset에서 블록을 둘로 나눔. 뒤쪽 글자가 새 블록으로 간다. 새 블록이 놓일 자리와 종류를 연산에 전부 적는다 | 위치 | 변환 |
| `merge(blockId, intoId, intoLength)` | 블록의 글자를 intoId 블록 끝에 붙이고, 블록을 비운 뒤 삭제 표시한다. intoLength는 합치기 직전 intoId 블록의 글 길이다 | 위치 | 변환 |
| `insertBlock(newId, parentId, pos, type, props, text)` | 새 블록 추가 | ID | 순서대로 적용 |
| `deleteBlock(blockId, deleted)` | 블록에 삭제 표시를 하거나(true) 표시를 지워 되살린다(false). 아래 블록은 함께 숨겨진다 | ID | 나중에 확정된 값이 남음 |
| `moveBlock(blockId, parentId, pos)` | 부모나 순서 변경. 들여쓰기도 이 연산이다 | ID | 순서대로 적용 + 검사 |
| `setProps(blockId, key, value)` | 블록 종류나 속성 변경 | ID | 나중에 확정된 값이 남음 |

클라이언트는 연산을 하나씩 보내지 않고 묶음(change)으로 보낸다. 묶음은 전부 적용되거나 전부 거부된다.

## 6.3 텍스트 연산의 모양

텍스트 연산은 블록의 첫 글자부터 끝까지 훑으면서 무엇을 할지 적은 목록이다. 조각은 세 가지다.

| 조각 | 뜻 |
| --- | --- |
| `retain(n)` | n글자를 그대로 두고 지나간다 |
| `retain(n, attrs)` | n글자의 서식을 바꾼다. 값이 `null` 이면 그 서식을 지운다 |
| `insert(text, attrs)` | 이 자리에 글자를 넣는다 |
| `delete(n)` | n글자를 지운다 |

예를 들어 "배포 일정 확정"에서 "일정" 뒤에 " 초안"을 넣는 연산은 `[retain(5), insert(" 초안")]` 이다. 뒤에 남은 글자는 적지 않아도 그대로 둔다는 뜻이다.

멘션은 글자 하나(U+FFFC)를 넣고 서식에 `mention` 을 다는 방식으로 표현한다. 댓글 위치도 `comment` 서식으로 표현한다. 둘 다 글자에 붙어 있으므로 텍스트 변환만 맞으면 위치가 저절로 따라간다.

## 6.4 서버 주도 OT의 흐름

서버가 문서마다 하나의 확정 순서(리비전 1, 2, 3...)를 만든다. 모든 참여자가 이 순서를 따르므로 변환 함수는 "두 연산을 어느 순서로 적용해도 같은 결과"라는 성질 하나(TP1)만 만족하면 된다. 서버 없이 참여자끼리 직접 주고받는 방식에서 요구되는 더 까다로운 성질(TP2)은 필요 없다. Jupiter 시스템과 Google Wave가 쓴 구조다.

**서버**

1. 클라이언트가 묶음을 보내면서 자기가 본 마지막 리비전(baseRev)을 함께 적는다.
2. 서버는 baseRev 이후에 확정된 연산들을 차례로 꺼내, 받은 묶음을 그 연산들에 맞게 변환한다.
3. 변환한 묶음을 현재 문서에 적용한다. 검사에 걸리면 묶음 전체를 거부한다(nack).
4. 로그에 새 리비전으로 커밋한 뒤 보낸 사람에게 ack, 나머지에게는 변환된 묶음을 보낸다.

**클라이언트**

클라이언트는 세 가지를 들고 있다. 서버가 확정한 문서, 보내고 ack를 기다리는 묶음 하나(inflight), 아직 보내지 않은 묶음(buffer)이다. 화면에 보이는 문서는 확정 문서에 inflight와 buffer를 적용한 결과다.

- 한 번에 하나의 묶음만 보낸다. ack가 오면 buffer를 다음 묶음으로 보낸다.
- 다른 사람의 연산이 오면 확정 문서에 적용하고, inflight와 buffer를 그 연산에 맞게 변환한 뒤 화면 문서를 다시 계산한다. inflight를 변환하는 순서와 함수가 서버와 같으므로 서버가 확정한 내 묶음과 클라이언트가 들고 있는 inflight는 항상 같은 값이다.
- 변환한 inflight를 지금 확정 문서에 적용할 수 없으면 화면에서만 빼 두고 서버의 판정을 기다린다. 뒤이어 오는 다른 연산 때문에 서버에서는 적용될 수 있기 때문이다.
- nack가 오면 inflight와 buffer를 버리고 화면을 확정 문서로 되돌린다.

## 6.5 위치 연산의 변환 규칙

아래 표에서 a는 변환할 내 연산, b는 먼저 확정된 연산이다. 서로 다른 블록을 다루면 a는 바뀌지 않는다. X, Y, Z, W는 블록이다.

| 내 연산 a | 먼저 확정된 b | 변환 결과 |
| --- | --- | --- |
| text(X) | text(X) | 고전적인 텍스트 변환. 같은 자리에 둘 다 삽입하면 먼저 확정된 글자가 앞에 온다. 같은 글자의 같은 서식을 둘 다 바꾸면 나중에 확정되는 a의 값이 남는다 |
| text(X) | split(X, k, Y) | a를 k에서 잘라 앞부분은 text(X), 뒷부분은 text(Y)로 나눈다. 정확히 k 자리의 삽입은 X의 끝에 남긴다 |
| text(Y) | merge(Y → X) | text(X)로 바꾸고 앞에 `retain(lenX)` 를 붙인다. lenX는 합치기 직전 X의 길이이고 서버가 확정할 때 연산에 적어 둔다 |
| split(X, k, Y) | text(X) | k를 b가 넣거나 지운 글자 수만큼 옮긴다 |
| split(X, k, Y) | split(X, j, Z) | k < j 이면 그대로 두고 Y를 X와 Z 사이에 둔다. k > j 이면 split(Z, k - j, Y). k = j 이면 빈 블록 Y를 X와 Z 사이에 넣는다 |
| split(X, k, Y) | merge(X → W) | split(W, k + lenW, Y) |
| merge(Y → X) | split(X, j, Z) | merge(Y → Z). Y 바로 앞 블록이 Z가 되었기 때문이다 |
| merge(Y → X) | merge(X → W) | merge(Y → W) |
| merge(Y → X) | merge(Y → X) | 아무것도 하지 않는 연산으로 바꾼다. 두 사람이 같은 Backspace를 누른 경우다 |
| merge(Y → X) | text(X) 또는 text(Y) | 그대로 둔다. 길이는 적용할 때 서버가 다시 잰다 |

Enter를 누른 사람과 그 뒤쪽에 글자를 치던 사람이 겹치는 경우가 두 번째 줄이다. 나누기를 "뒤쪽 글자 삭제 + 새 블록 추가"로 표현했다면 뒤쪽에 치던 글자가 앞 블록 끝에 남는다. 나누기를 독립된 연산으로 두면 그 글자가 새 블록을 따라간다. 이 설계에서 변환을 직접 구현하는 이유가 여기에 있다.

## 6.6 ID 연산의 적용 규칙

ID로 가리키는 연산은 변환하지 않고, 확정 순서대로 적용하면서 아래 규칙을 검사한다.

| 상황 | 규칙 |
| --- | --- |
| 삭제된 블록에 text, setProps, insertBlock(자식 추가)이 옴 | 삭제 표시된 블록에 그대로 적용한다. 화면에는 보이지 않지만, 삭제를 되돌리면 그 편집도 함께 돌아온다 |
| 삭제된 블록에 split, merge가 옴 | 묶음을 거부한다 |
| 같은 블록을 두 사람이 다른 곳으로 옮김 | 나중에 확정된 이동이 남는다 |
| 이동 결과 블록이 자기 자손 아래로 들어감 (A를 B 아래로, 동시에 B를 A 아래로) | 나중에 도착한 묶음을 거부한다 |
| merge를 적용하려는데 합쳐질 블록 아래에 삭제되지 않은 자식이 남아 있음 | 묶음을 거부한다. 클라이언트는 자식을 같은 묶음 안에서 먼저 다른 곳으로 옮긴 뒤 합친다 |
| 같은 자리에 두 사람이 블록을 넣어 `pos` 가 같아짐 | (pos, 블록 ID) 순으로 정렬한다. 모든 참여자가 같은 순서를 본다 |
| 합쳐지는 블록 아래로 다른 사람이 동시에 블록을 넣거나 옮김 | 나중에 도착한 묶음을 거부한다 |

거부는 사용자가 방금 한 입력이 되돌아가는 경험이라 드물어야 한다. 가장 흔한 충돌인 "지워진 블록 안에서 편집"을 거부하지 않도록 삭제 표시 방식을 택했다. 거부율은 지표로 남기고 부하 실험에서 수치를 확인한다. 삭제 표시된 블록은 30일 뒤 스냅샷을 만들 때 실제로 지운다.

## 6.7 되돌리기

되돌리기는 내가 한 편집만 대상으로 한다. 연산을 적용할 때 그 역연산을 함께 계산해 내 되돌리기 목록에 쌓는다. 다른 사람의 연산이 들어올 때마다 목록 안의 역연산도 같이 변환해 둔다. 되돌리기를 누르면 맨 위의 역연산을 보통 편집처럼 서버로 보낸다.

## 6.8 Java 구현 모양

`ot-core` 는 상태를 갖지 않는 함수 네 개를 제공한다. 적용(`Document.apply`), 변환(`Transformer.transform`), 합성(`Delta.compose`, `Changes.append`), 역연산(`Inverter.invert`)이다. 그 위에 서버와 클라이언트의 동기화 규칙을 `ServerDocument` 와 `ClientSession` 으로 얹었다. 둘 다 저장소와 네트워크를 모르는 순수 로직이라 가짜 네트워크로 수렴을 검사할 수 있다.

```java
public sealed interface Op
        permits Op.TextOp, Op.SplitBlock, Op.MergeBlock, Op.InsertBlock, Op.DeleteBlock, Op.MoveBlock, Op.SetProps {

    String blockId();

    record TextOp(String blockId, Delta delta) implements Op {}
    record MergeBlock(String blockId, String intoId, int intoLength) implements Op {}
    // 나머지 5종 생략
}

public final class Transformer {

    /** 연산 a 를, b 가 먼저 적용된 뒤의 문서에 맞게 바꾼다. aIsLater 는 a 가 b 보다 나중에 확정되는지다. */
    public static List<Op> transform(Op a, Op b, boolean aIsLater) {
        return switch (a) {
            case TextOp text -> transformText(text, b, aIsLater);
            case SplitBlock split -> transformSplit(split, b, aIsLater);
            case MergeBlock merge -> transformMerge(merge, b);
            case InsertBlock insert -> transformInsert(insert, b);
            case DeleteBlock delete -> transformDelete(delete, b, aIsLater);
            case MoveBlock move -> transformMove(move, b, aIsLater);
            case SetProps setProps -> transformSetProps(setProps, b, aIsLater);
        };
    }
}
```

sealed interface라서 연산 종류를 하나 더 만들면 이 switch가 컴파일되지 않는다. 빠진 조합을 테스트가 아니라 컴파일러가 먼저 알려 준다.

브라우저 쪽은 같은 규칙을 TypeScript로 한 번 더 구현했다(`frontend/src/collab/ot/`, 상태 기계는 `frontend/src/collab/client/`). 연산은 `op` 필드로 종류를 가르는 유니온 타입이고, 종류별 switch 끝에 `assertNever` 를 두어 빠진 분기를 타입 검사가 잡게 했다. 두 구현이 같은 결과를 내는지는 `ot-core` 가 내보내는 적합성 벡터로 확인한다(ADR 0009). 벡터의 JSON 형식은 7.2의 연산 형식을 따른다.

`aIsLater` 가 필요한 이유는 한쪽을 골라야 하는 경우 때문이다. 같은 자리에 둘 다 글자를 넣으면 먼저 확정된 쪽의 글자가 앞에 오고, 같은 값을 둘 다 바꾸면 나중에 확정된 쪽의 값이 남는다. 두 방향의 변환이 같은 쪽을 골라야 결과가 같아진다.

## 6.9 참고 자료

- [High-latency, low-bandwidth windowing in the Jupiter collaboration system](https://dl.acm.org/doi/10.1145/215585.215706): 서버 주도 OT의 원형이 된 논문
- [Google Wave Operational Transformation](https://svn.apache.org/repos/asf/incubator/wave/whitepapers/operational-transform/operational-transform.html): 한 번에 하나의 묶음만 보내고 ack를 기다리는 클라이언트 규칙
- [How Figma's multiplayer technology works](https://www.figma.com/blog/how-figmas-multiplayer-technology-works/): ID로 가리키는 객체를 서버 순서로 확정하는 방식, 분수 인덱스, 부모 변경의 순환 거부
- [Collaborative Editing in ProseMirror](https://marijnhaverbeke.nl/blog/collaborative-editing.html): 중앙 서버가 순서를 정하는 편집기의 재정렬 방식

## 6.10 구현하면서 바뀐 점

1단계(OT 코어)를 구현하면서 처음 설계에서 다섯 가지를 바꿨다. 위의 표와 본문은 바뀐 내용으로 고쳐 두었다.

| 바뀐 점 | 처음 설계 | 지금 | 이유 |
| --- | --- | --- | --- |
| split이 갖는 정보 | 새 블록 ID와 순서 키 | 새 블록의 부모, 순서 키, 종류, 속성까지 전부 | 나누려던 블록이 그 사이 다른 블록에 합쳐지면, 변환 함수는 문서를 볼 수 없어서 새 블록의 부모와 종류를 알 방법이 없다. 연산에 적혀 있어야 두 순서의 결과가 같아진다 |
| 합쳐진 블록의 처리 | 블록을 없앰 | 글을 비우고 삭제 표시 | 블록을 없애면 그 아래 숨어 있던 삭제 표시 블록들이 부모를 잃는다. 또 같은 블록을 동시에 옮기거나 속성을 바꾼 연산을 버릴 필요 없이 그대로 적용할 수 있다 |
| 합치기의 이웃 검사 | 두 블록이 문서 순서상 이웃이 아니면 거부 | 검사하지 않음 | 이웃인지는 편집기가 Backspace를 처리할 때 이미 안다. 코어에서 다시 검사하면 두 블록 사이에 누가 블록을 끼워 넣은 것만으로 합치기가 거부된다 |
| 합쳐지는 블록의 자식 | 서버가 자식을 위로 올림 | 자식이 남아 있으면 거부. 클라이언트가 먼저 옮긴다 | 자식을 어느 순서 키로 올릴지를 서버가 정하면 형제들과 순서가 섞인다. 화면을 아는 클라이언트가 자리를 정하는 편이 맞다 |
| 삭제의 되돌리기 | deleteBlock만 있음 | deleteBlock이 삭제와 되살리기를 모두 표현 | 되돌리기에 필요하다. 연산 종류를 늘리지 않으려고 값(true, false)으로 구분했다 |

아직 구현하지 않은 것도 있다.

- 댓글 서식(`comment`)을 목록으로 합치는 규칙(10.2). 지금은 다른 서식과 같이 나중에 확정된 값이 남는다. 5단계에서 댓글을 만들 때 넣는다.
- 되돌리기 목록. 역연산 계산(`Inverter`)은 있고, 다른 사람의 연산이 올 때마다 목록 안의 역연산을 변환해 두는 부분은 5단계에서 만든다.
