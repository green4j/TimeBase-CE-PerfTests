package io.github.green4j.timebase.perftests;

import java.util.Arrays;

/**
 * Summary of one metric over the runs of one server in one scenario.
 */
final class Stats {

    final int n;
    final double mean;
    final double std;
    final double median;
    final double q1;
    final double q3;
    final double min;
    final double max;

    private Stats(int n, double mean, double std, double median, double q1, double q3, double min, double max) {
        this.n = n;
        this.mean = mean;
        this.std = std;
        this.median = median;
        this.q1 = q1;
        this.q3 = q3;
        this.min = min;
        this.max = max;
    }

    static Stats of(double[] values) {
        final int n = values.length;
        if (n == 0)
            throw new IllegalArgumentException("values must not be empty");
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (double v : values)
            sum += v;
        final double mean = sum / n;
        double squares = 0;
        for (double v : values)
            squares += (v - mean) * (v - mean);
        final double std = n > 1 ? Math.sqrt(squares / (n - 1)) : 0;
        return new Stats(n, mean, std, quantile(sorted, 0.5), quantile(sorted, 0.25), quantile(sorted, 0.75),
                sorted[0], sorted[n - 1]);
    }

    /** Linear interpolation between the closest ranks of sorted values. */
    static double quantile(double[] sorted, double q) {
        final double position = q * (sorted.length - 1);
        final int lower = (int) Math.floor(position);
        final int upper = (int) Math.ceil(position);
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower);
    }

    private static final double[] T_975 = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060,
            2.056, 2.052, 2.048, 2.045, 2.042};

    /**
     * The 97.5 % quantile of Student's t distribution with {@code df} degrees of freedom, the factor of a two-sided
     * 95 % interval: a table up to 30, beyond it the Cornish-Fisher expansion around the normal 1.96.
     */
    static double t975(int df) {
        if (df < 1)
            throw new IllegalArgumentException("df must be at least 1, got " + df);
        if (df <= T_975.length)
            return T_975[df - 1];
        final double z = 1.959964;
        return z + (z * z * z + z) / (4 * df) + (5 * Math.pow(z, 5) + 16 * z * z * z + 3 * z) / (96.0 * df * df);
    }

    /** Coefficient of variation, percent. */
    double cv() {
        return mean != 0 ? std / Math.abs(mean) * 100 : 0;
    }
}
