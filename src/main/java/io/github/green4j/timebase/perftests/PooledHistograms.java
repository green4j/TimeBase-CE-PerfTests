package io.github.green4j.timebase.perftests;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramIterationValue;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * The histograms of several runs over common buckets, so that a percentile of any multiset of the runs, a bootstrap
 * resample included, is found without merging histograms.
 */
final class PooledHistograms {

    /** The highest equivalent value of every bucket any run has a count in, ascending. */
    private final long[] values;
    /** Per run, the count at or below each of {@link #values}. */
    private final long[][] cumulative;

    PooledHistograms(List<Histogram> runs) {
        final TreeSet<Long> all = new TreeSet<>();
        for (Histogram h : runs) {
            for (HistogramIterationValue v : h.recordedValues())
                all.add(v.getValueIteratedTo());
        }
        values = all.stream().mapToLong(Long::longValue).toArray();
        cumulative = new long[runs.size()][];
        for (int r = 0; r < runs.size(); r++) {
            final long[] c = new long[values.length];
            for (HistogramIterationValue v : runs.get(r).recordedValues())
                c[Arrays.binarySearch(values, v.getValueIteratedTo())] += v.getCountAtValueIteratedTo();
            for (int k = 1; k < c.length; k++)
                c[k] += c[k - 1];
            cumulative[r] = c;
        }
    }

    int runs() {
        return cumulative.length;
    }

    /**
     * The value at {@code percentile} of the runs merged, run {@code r} counted {@code weights[r]} times, as
     * {@link Histogram#getValueAtPercentile} gives it for the merged histogram; 0 without counts.
     */
    long valueAtPercentile(int[] weights, double percentile) {
        if (values.length == 0)
            return 0;
        final long total = countAtOrBelow(weights, values.length - 1);
        if (total == 0)
            return 0;
        final double requested = Math.min(Math.max(Math.nextAfter(percentile, Double.NEGATIVE_INFINITY), 0), 100);
        final long count = Math.max(1, (long) Math.ceil(requested / 100 * total));
        int lo = 0;
        int hi = values.length - 1;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (countAtOrBelow(weights, mid) >= count)
                hi = mid;
            else
                lo = mid + 1;
        }
        return values[lo];
    }

    private long countAtOrBelow(int[] weights, int k) {
        long sum = 0;
        for (int r = 0; r < cumulative.length; r++)
            sum += weights[r] * cumulative[r][k];
        return sum;
    }
}
