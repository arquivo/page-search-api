package pt.arquivo.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class TimelineTest {

    private Map<String, Long> perYear(long... countsPerYear) {
        Map<String, Long> perYear = new LinkedHashMap<>();
        for (int i = 0; i < countsPerYear.length; i++) {
            perYear.put(String.valueOf(2005 + i), countsPerYear[i]);
        }
        return perYear;
    }

    @Test
    public void impactIsTheShareOfTheYear() {
        Timeline timeline = Timeline.of(perYear(1200, 1800), perYear(100000, 100000));

        assertThat(timeline.getCounts()).containsExactly(entry("2005", 1200L), entry("2006", 1800L));
        assertThat(timeline.getImpact().get("2005")).isEqualByComparingTo(new BigDecimal("0.012"));
        assertThat(timeline.getImpact().get("2006")).isEqualByComparingTo(new BigDecimal("0.018"));
    }

    @Test
    public void yearsWithoutMatchesAreKept() {
        Timeline timeline = Timeline.of(perYear(0, 1800), perYear(100000, 100000));

        assertThat(timeline.getCounts().get("2005")).isEqualTo(0L);
        assertThat(timeline.getImpact().get("2005")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    public void yearsTheArchiveHasNothingForHaveNoImpact() {
        // A year the baseline doesn't know about, and one the archive holds nothing for: no division to be made
        Map<String, Long> totals = perYear(0);
        Timeline timeline = Timeline.of(perYear(10, 20), totals);

        assertThat(timeline.getImpact().get("2005")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(timeline.getImpact().get("2006")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    public void rareMatchesStillShowUp() {
        // One document out of a 20 million document year is still not zero
        Timeline timeline = Timeline.of(perYear(1), perYear(20000000));

        assertThat(timeline.getImpact().get("2005")).isEqualByComparingTo(new BigDecimal("0.00000005"));
    }
}
