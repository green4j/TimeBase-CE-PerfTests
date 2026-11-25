package io.github.green4j.timebase.perftests;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PooledHistogramsTest {

    @Test
    void givesThePercentilesOfTheMergedHistogram() {
        final SplittableRandom random = new SplittableRandom(7);
        final List<Histogram> runs = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            final Histogram h = new Histogram(3);
            for (int i = 0; i < 5_000 + r * 1_000; i++)
                h.recordValue(1_000 + (long) (Math.exp(random.nextDouble() * 12) * (r + 1)));
            runs.add(h);
        }
        final PooledHistograms pooled = new PooledHistograms(runs);
        for (int[] weights : new int[][] {{1, 1, 1, 1}, {0, 2, 1, 1}, {4, 0, 0, 0}, {0, 0, 3, 1}}) {
            final Histogram merged = new Histogram(3);
            for (int r = 0; r < runs.size(); r++) {
                for (int k = 0; k < weights[r]; k++)
                    merged.add(runs.get(r));
            }
            for (double p : new double[] {50, 90, 99, 99.9, 99.99})
                assertEquals(merged.getValueAtPercentile(p), pooled.valueAtPercentile(weights, p),
                        "p" + p + " differs from the merged histogram for weights " + java.util.Arrays.toString(weights));
            assertEquals(merged.getMaxValue(), pooled.valueAtPercentile(weights, 100),
                    "The max differs from the merged histogram for weights " + java.util.Arrays.toString(weights));
        }
    }

    @Test
    void runsWithoutCountsGiveZero() {
        final PooledHistograms pooled = new PooledHistograms(List.of(new Histogram(3)));
        assertEquals(0, pooled.valueAtPercentile(new int[] {1}, 99), "An empty run gave a value");
    }
}
