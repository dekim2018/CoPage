package com.copage.ot.support;

/** 무작위 테스트의 반복 횟수. 평소 빌드는 빠르게 돌리고, 오래 돌릴 때는 시스템 속성으로 늘린다. */
public final class Iterations {

    private Iterations() {
    }

    /**
     * {@code -Dcopage.ot.scale=50} 처럼 주면 모든 무작위 테스트를 그 배수만큼 더 돌린다.
     *
     * @param base 배수가 1 일 때의 횟수
     */
    public static int of(int base) {
        return (int) Math.min(Integer.MAX_VALUE, (long) base * Integer.getInteger("copage.ot.scale", 1));
    }
}
