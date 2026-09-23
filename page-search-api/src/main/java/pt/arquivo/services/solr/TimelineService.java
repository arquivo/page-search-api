package pt.arquivo.services.solr;

import java.util.Map;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;

import pt.arquivo.services.SearchQuery;
import pt.arquivo.services.Timeline;

/**
 * Computes the timeline of a query: how many documents match on each year of the archive, and how big that is when
 * compared to everything the archive holds for that year.
 *
 * <p>The counts come from a Solr range facet over the year field, which always spans the whole archive: the facet
 * buckets by {@code dateOldest} while the query's {@code from} filters by {@code dateLatest} (see
 * {@code SolrSearchService#convertSearchQuery}), so a long-lived document can match a {@code from} filter while
 * still being bucketed into a year before it. The totals that normalize the counts are the volumes of the archive
 * itself, which are shared with the year balance ranking, see {@link YearVolumes}.
 */
public class TimelineService {

    private final YearVolumes yearVolumes;

    public TimelineService(YearVolumes yearVolumes) {
        this.yearVolumes = yearVolumes;
    }

    /**
     * Asks Solr for the number of matching documents per year.
     *
     * @param solrQuery the query to facet, it is modified in place
     */
    void addYearRangeFacet(SolrQuery solrQuery) {
        yearVolumes.addYearRangeFacet(solrQuery);
    }

    /**
     * Builds the timeline of a query out of its faceted reply, normalizing the counts by the size of the archive on
     * each year.
     *
     * <p>Years outside the query's {@code from}/{@code to} range are zeroed out: the range facet always spans the
     * whole archive, and a query bounded by {@code from}/{@code to} can still have a residual, non representative
     * count on the years just outside it (see the class javadoc), which would otherwise show up as a confusing,
     * non zero impact for years the caller explicitly excluded.
     *
     * @param searchQuery the query the timeline is for, read for its {@code from}/{@code to} bounds
     */
    Timeline buildTimeline(QueryResponse queryResponse, SearchQuery searchQuery) {
        Map<String, Long> counts = YearVolumes.parseYearlyCounts(queryResponse);
        clearOutOfRangeYears(counts, searchQuery);
        return Timeline.of(counts, yearVolumes.perYear());
    }

    private static void clearOutOfRangeYears(Map<String, Long> counts, SearchQuery searchQuery) {
        Integer fromYear = yearOf(searchQuery.getFrom());
        Integer toYear = yearOf(searchQuery.getTo());
        if (fromYear == null && toYear == null) {
            return;
        }
        for (String year : counts.keySet()) {
            int y = Integer.parseInt(year);
            if ((fromYear != null && y < fromYear) || (toYear != null && y > toYear)) {
                counts.put(year, 0L);
            }
        }
    }

    private static Integer yearOf(String timestamp) {
        if (timestamp == null || timestamp.length() < 4) {
            return null;
        }
        return Integer.parseInt(timestamp.substring(0, 4));
    }
}
