package pt.arquivo.services.solr;

import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import pt.arquivo.services.SearchQuery;
import pt.arquivo.services.SearchQueryImpl;
import pt.arquivo.services.Timeline;

import static org.assertj.core.api.Assertions.assertThat;

public class TimelineServiceTest {

    private static QueryResponse queryResponseWithYearRangeFacet(Map<String, Integer> countsByBucket) {
        NamedList<Object> counts = new NamedList<>();
        for (Map.Entry<String, Integer> entry : countsByBucket.entrySet()) {
            counts.add(entry.getKey(), entry.getValue());
        }

        NamedList<Object> facet = new NamedList<>();
        facet.add("gap", "+1YEAR");
        facet.add("counts", counts);

        NamedList<Object> facetRanges = new NamedList<>();
        facetRanges.add(YearVolumes.YEAR_FIELD, facet);

        NamedList<Object> facetCounts = new NamedList<>();
        facetCounts.add("facet_ranges", facetRanges);

        NamedList<Object> response = new NamedList<>();
        response.add("facet_counts", facetCounts);

        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(response);
        return queryResponse;
    }

    private static QueryResponse queryResponseFor1996To2000() {
        Map<String, Integer> byBucket = new LinkedHashMap<>();
        byBucket.put("1996-01-01T00:00:00Z", 92);
        byBucket.put("1997-01-01T00:00:00Z", 2017);
        byBucket.put("2000-01-01T00:00:00Z", 188514);
        return queryResponseWithYearRangeFacet(byBucket);
    }

    private static Map<String, Long> totals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("1996", 29000L);
        totals.put("1997", 219000L);
        totals.put("2000", 1043700L);
        return totals;
    }

    private static SearchQuery searchQueryWithRange(String from, String to) {
        SearchQueryImpl searchQuery = new SearchQueryImpl("Lisboa");
        if (from != null) {
            searchQuery.setFrom(from);
        }
        if (to != null) {
            searchQuery.setTo(to);
        }
        return searchQuery;
    }

    @Test
    public void buildTimeline_noRange_keepsCountsAndImpactAsIs() {
        TimelineService timelineService = new TimelineService(new YearVolumes(totals()));

        Timeline timeline = timelineService.buildTimeline(queryResponseFor1996To2000(), searchQueryWithRange(null, null));

        assertThat(timeline.getCounts()).containsEntry("1996", 92L);
        assertThat(timeline.getImpact().get("1996")).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    public void buildTimeline_from2000_zeroesCountsAndImpactBeforeFrom() {
        TimelineService timelineService = new TimelineService(new YearVolumes(totals()));

        Timeline timeline = timelineService.buildTimeline(queryResponseFor1996To2000(), searchQueryWithRange("2000", null));

        assertThat(timeline.getCounts()).containsEntry("1996", 0L).containsEntry("1997", 0L).containsEntry("2000", 188514L);
        assertThat(timeline.getImpact().get("1996")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(timeline.getImpact().get("1997")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(timeline.getImpact().get("2000")).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    public void buildTimeline_to1997_zeroesCountsAndImpactAfterTo() {
        TimelineService timelineService = new TimelineService(new YearVolumes(totals()));

        Timeline timeline = timelineService.buildTimeline(queryResponseFor1996To2000(), searchQueryWithRange(null, "1997"));

        assertThat(timeline.getCounts()).containsEntry("1996", 92L).containsEntry("1997", 2017L).containsEntry("2000", 0L);
        assertThat(timeline.getImpact().get("2000")).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
