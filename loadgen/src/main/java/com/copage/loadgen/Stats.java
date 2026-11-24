package com.copage.loadgen;

import java.util.ArrayList;
import java.util.List;

/** 지연 표본과 백분위. 표본은 ms 다. */
final class Stats {

    private final List<Double> samples = new ArrayList<>();

    synchronized void add(double ms) {
        samples.add(ms);
    }

    synchronized int count() {
        return samples.size();
    }

    synchronized double percentile(double p) {
        if (samples.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(samples);
        sorted.sort(null);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    String summary() {
        return String.format("n=%d p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms",
                count(), percentile(50), percentile(95), percentile(99), percentile(100));
    }
}
