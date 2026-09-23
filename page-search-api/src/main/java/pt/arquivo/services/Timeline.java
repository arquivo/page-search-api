package pt.arquivo.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How the documents matching a query are spread over the years of the archive.
 *
 * <p>The counts alone say little, the archive is much bigger on some years than on others, so each year also gets an
 * impact: the share of that year's archived documents that match the query.
 */
public class Timeline {

    /** Number of matching documents per year, e.g. {"2005": 1200}. Years without matches are kept with a count of 0. */
    @Schema(description = "Number of matching documents per year, e.g. {\"2005\": 1200}. Years without matches are kept with a count of 0. "
            + "When the query is bounded by from/to, years outside that range are also kept at 0: from/to match a page by its most "
            + "recent/oldest capture rather than the one this count is bucketed by, so without this a year just outside the "
            + "requested range could otherwise show a small, non representative count.")
    @JsonProperty("counts")
    private final Map<String, Long> counts;

    /** matches of the year / documents archived on that year, e.g. {"2005": 0.012}. */
    @Schema(description = "Share of that year's archived documents that match the query, e.g. {\"2005\": 0.012}. Follows the same from/to "
            + "zeroing as counts: years outside the requested range are always 0 here, even though the underlying search may "
            + "still include some documents originating outside that range.")
    @JsonProperty("impact")
    private final Map<String, BigDecimal> impact;

    /** Impact is a small ratio, keep enough decimals for the rare years to still show up as non zero. */
    private static final int IMPACT_SCALE = 10;

    private Timeline(Map<String, Long> counts, Map<String, BigDecimal> impact) {
        this.counts = counts;
        this.impact = impact;
    }

    /**
     * Normalizes the counts of a query by the total number of documents archived on each year.
     *
     * @param counts matching documents per year
     * @param totalsPerYear documents in the whole archive per year, the years missing from it get an impact of 0
     * @return the timeline of the query
     */
    public static Timeline of(Map<String, Long> counts, Map<String, Long> totalsPerYear) {
        Map<String, BigDecimal> impact = new LinkedHashMap<>();
        for (Map.Entry<String, Long> yearCount : counts.entrySet()) {
            Long total = totalsPerYear.get(yearCount.getKey());
            if (total == null || total <= 0) {
                impact.put(yearCount.getKey(), BigDecimal.ZERO.setScale(IMPACT_SCALE));
                continue;
            }
            impact.put(yearCount.getKey(), BigDecimal.valueOf(yearCount.getValue())
                    .divide(BigDecimal.valueOf(total), IMPACT_SCALE, RoundingMode.HALF_UP));
        }
        return new Timeline(Collections.unmodifiableMap(new LinkedHashMap<>(counts)),
                Collections.unmodifiableMap(impact));
    }

    public Map<String, Long> getCounts() {
        return counts;
    }

    public Map<String, BigDecimal> getImpact() {
        return impact;
    }
}
