package com.copage.ot.text;

import java.util.Map;

/** 텍스트 연산을 이루는 조각. 블록의 첫 글자부터 차례로 읽으며 적용한다. */
public sealed interface Component permits Component.Retain, Component.Insert, Component.Delete {

    /** 문서의 글자를 몇 개 소비하는지. 삽입은 0 이다. */
    int baseLength();

    /** 조각 자체의 길이. 삽입은 넣는 글자 수다. */
    int length();

    /** 글자를 그대로 두고 지나간다. attrs 가 있으면 그 글자들의 서식을 바꾼다. */
    record Retain(int count, Map<String, Object> attrs) implements Component {
        public Retain {
            if (count <= 0) {
                throw new IllegalArgumentException("retain 길이는 1 이상이어야 한다");
            }
            attrs = Attrs.copy(attrs);
        }

        @Override
        public int baseLength() {
            return count;
        }

        @Override
        public int length() {
            return count;
        }
    }

    /** 이 자리에 글자를 넣는다. */
    record Insert(String text, Map<String, Object> attrs) implements Component {
        public Insert {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("빈 문자열은 삽입할 수 없다");
            }
            attrs = Attrs.merge(Attrs.NONE, Attrs.copy(attrs), false);
        }

        @Override
        public int baseLength() {
            return 0;
        }

        @Override
        public int length() {
            return text.length();
        }
    }

    /** 글자를 지운다. */
    record Delete(int count) implements Component {
        public Delete {
            if (count <= 0) {
                throw new IllegalArgumentException("delete 길이는 1 이상이어야 한다");
            }
        }

        @Override
        public int baseLength() {
            return count;
        }

        @Override
        public int length() {
            return count;
        }
    }
}
