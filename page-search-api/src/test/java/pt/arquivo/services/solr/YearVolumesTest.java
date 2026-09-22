package pt.arquivo.services.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.junit.Test;

import java.io.IOException;
import java.time.Year;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class YearVolumesTest {

    private static QueryResponse queryResponseWithYearRangeFacet(String fieldName, Map<String, Integer> countsByBucket) {
        NamedList<Object> counts = new NamedList<>();
        for (Map.Entry<String, Integer> entry : countsByBucket.entrySet()) {
            counts.add(entry.getKey(), entry.getValue());
        }

        NamedList<Object> facet = new NamedList<>();
        facet.add("gap", "+1YEAR");
        facet.add("start", "2000-01-01T00:00:00Z");
        facet.add("end", "2021-01-01T00:00:00Z");
        facet.add("counts", counts);

        NamedList<Object> facetRanges = new NamedList<>();
        facetRanges.add(fieldName, facet);

        NamedList<Object> facetCounts = new NamedList<>();
        facetCounts.add("facet_ranges", facetRanges);

        NamedList<Object> response = new NamedList<>();
        response.add("facet_counts", facetCounts);

        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(response);
        return queryResponse;
    }

    @Test
    public void parseYearlyCounts_noFacetCounts_returnsEmptyMap() {
        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(new NamedList<>());

        assertThat(YearVolumes.parseYearlyCounts(queryResponse)).isEmpty();
    }

    @Test
    public void parseYearlyCounts_facetForOtherField_isSkipped() {
        QueryResponse queryResponse = queryResponseWithYearRangeFacet("someOtherField",
                Collections.singletonMap("2010-01-01T00:00:00Z", 4));

        assertThat(YearVolumes.parseYearlyCounts(queryResponse)).isEmpty();
    }

    @Test
    public void parseYearlyCounts_dateOldestField_returnsCountsKeyedByYear() {
        Map<String, Integer> byBucket = new LinkedHashMap<>();
        byBucket.put("2000-01-01T00:00:00Z", 3);
        byBucket.put("2001-01-01T00:00:00Z", 7);
        QueryResponse queryResponse = queryResponseWithYearRangeFacet(YearVolumes.YEAR_FIELD, byBucket);

        Map<String, Long> counts = YearVolumes.parseYearlyCounts(queryResponse);

        assertThat(counts).containsEntry("2000", 3L).containsEntry("2001", 7L);
    }

    @Test
    public void constructorWithFixedMap_emptyMap_startYearFallsBackToCurrentYear() {
        YearVolumes yearVolumes = new YearVolumes(Collections.emptyMap());

        assertThat(yearVolumes.getStartYear()).isEqualTo(Year.now().getValue());
    }

    @Test
    public void constructorWithFixedMap_nonEmptyMap_startYearIsSmallestYear() {
        Map<String, Long> perYear = new LinkedHashMap<>();
        perYear.put("2010", 5L);
        perYear.put("2005", 2L);
        perYear.put("2015", 9L);

        YearVolumes yearVolumes = new YearVolumes(perYear);

        assertThat(yearVolumes.getStartYear()).isEqualTo(2005);
    }

    @Test
    public void perYear_firstCall_fetchesAndCachesResult() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(
                queryResponseWithYearRangeFacet(YearVolumes.YEAR_FIELD, Collections.singletonMap("2020-01-01T00:00:00Z", 5)));
        YearVolumes yearVolumes = new YearVolumes(solrClient, 2020, Long.MAX_VALUE);

        Map<String, Long> firstCall = yearVolumes.perYear();
        Map<String, Long> secondCall = yearVolumes.perYear();

        assertThat(firstCall).containsEntry("2020", 5L);
        assertThat(secondCall).isSameAs(firstCall);
        verify(solrClient, times(1)).query(any(SolrQuery.class));
    }

    @Test
    public void perYear_cacheExpired_reFetchesFromSolr() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.query(any(SolrQuery.class)))
                .thenReturn(queryResponseWithYearRangeFacet(YearVolumes.YEAR_FIELD, Collections.singletonMap("2020-01-01T00:00:00Z", 5)))
                .thenReturn(queryResponseWithYearRangeFacet(YearVolumes.YEAR_FIELD, Collections.singletonMap("2020-01-01T00:00:00Z", 9)));
        YearVolumes yearVolumes = new YearVolumes(solrClient, 2020, 20L);

        Map<String, Long> firstCall = yearVolumes.perYear();
        Thread.sleep(50);
        Map<String, Long> secondCall = yearVolumes.perYear();

        assertThat(firstCall).containsEntry("2020", 5L);
        assertThat(secondCall).containsEntry("2020", 9L);
        verify(solrClient, times(2)).query(any(SolrQuery.class));
    }

    @Test
    public void perYear_solrServerExceptionOnFirstCall_returnsEmptyMap() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenThrow(new SolrServerException("boom"));
        YearVolumes yearVolumes = new YearVolumes(solrClient, 2020, Long.MAX_VALUE);

        assertThat(yearVolumes.perYear()).isEmpty();
    }

    @Test
    public void perYear_ioExceptionOnFirstCall_returnsEmptyMap() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenThrow(new IOException("boom"));
        YearVolumes yearVolumes = new YearVolumes(solrClient, 2020, Long.MAX_VALUE);

        assertThat(yearVolumes.perYear()).isEmpty();
    }

    @Test
    public void perYear_exceptionAfterCacheExpiry_keepsPreviousCachedValue() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.query(any(SolrQuery.class)))
                .thenReturn(queryResponseWithYearRangeFacet(YearVolumes.YEAR_FIELD, Collections.singletonMap("2020-01-01T00:00:00Z", 5)))
                .thenThrow(new SolrServerException("boom"));
        YearVolumes yearVolumes = new YearVolumes(solrClient, 2020, 20L);

        Map<String, Long> firstCall = yearVolumes.perYear();
        Thread.sleep(50);
        Map<String, Long> secondCall = yearVolumes.perYear();

        assertThat(secondCall).isSameAs(firstCall);
        assertThat(secondCall).containsEntry("2020", 5L);
    }
}
