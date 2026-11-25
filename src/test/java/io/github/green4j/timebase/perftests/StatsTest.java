package io.github.green4j.timebase.perftests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsTest {

    @Test
    void summarisesValues() {
        final Stats s = Stats.of(new double[] {2, 4, 4, 4, 5, 5, 7, 9});

        assertEquals(8, s.n);
        assertEquals(5.0, s.mean, 1e-9, "Wrong mean");
        assertEquals(2.138, s.std, 1e-3, "Wrong sample standard deviation");
        assertEquals(4.5, s.median, 1e-9, "Wrong median of an even count");
        assertEquals(2, s.min, 1e-9);
        assertEquals(9, s.max, 1e-9);
    }

    @Test
    void quartilesInterpolateBetweenRanks() {
        final Stats s = Stats.of(new double[] {1, 2, 3, 4});

        assertEquals(1.75, s.q1, 1e-9, "Wrong first quartile");
        assertEquals(2.5, s.median, 1e-9, "Wrong median");
        assertEquals(3.25, s.q3, 1e-9, "Wrong third quartile");
    }

    @Test
    void singleValueHasNoSpread() {
        final Stats s = Stats.of(new double[] {3});

        assertEquals(0, s.std, 1e-9, "A single run reported a spread");
        assertEquals(3, s.median, 1e-9);
    }

    @Test
    void tQuantileFollowsTheTableAndTendsToTheNormal() {
        assertEquals(12.706, Stats.t975(1), 1e-3, "Wrong t for 1 degree of freedom");
        assertEquals(2.042, Stats.t975(30), 1e-3, "Wrong t for 30 degrees of freedom");
        assertEquals(2.021, Stats.t975(40), 1e-3, "The expansion beyond the table is off");
        assertEquals(1.984, Stats.t975(100), 1e-3, "The expansion beyond the table is off");
        assertEquals(1.960, Stats.t975(100_000), 1e-3, "t does not tend to the normal quantile");
    }
}
