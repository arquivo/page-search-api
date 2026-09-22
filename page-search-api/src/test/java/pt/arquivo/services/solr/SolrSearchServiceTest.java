package pt.arquivo.services.solr;

import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import pt.arquivo.services.SearchQuery;
import pt.arquivo.services.SearchQueryImpl;
import pt.arquivo.services.SearchResult;
import pt.arquivo.services.SearchResultSolrImpl;
import pt.arquivo.services.SearchResults;
import pt.arquivo.services.SearchServiceConfiguration;

import java.io.IOException;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SolrSearchServiceTest {

    private SolrSearchService service;

    @Before
    public void setUp() {
        SearchServiceConfiguration configuration = new SearchServiceConfiguration();
        configuration.setStartDate("19960101000000");
        configuration.setServiceName("http://localhost:8081");
        configuration.setScreenshotServiceEndpoint("http://screenshot.example.com");
        configuration.setWaybackServiceEndpoint("http://wayback.example.com/wayback");
        configuration.setWaybackNoFrameServiceEndpoint("http://wayback.example.com/noFrame");
        configuration.setExtractedTextServiceEndpoint("http://extractedtext.example.com");
        configuration.setBaseSolrUrl("http://solr.example.com/solr/searchpages");
        configuration.setTextSearchServiceEndpoint("http://textsearch.example.com");
        service = new SolrSearchService(configuration);

        // An archive starting on 1996 and holding ten times more documents on 2008 than on 1997, so that the volumes
        // are known without a Solr to ask
        Map<String, Long> volumesPerYear = new LinkedHashMap<>();
        volumesPerYear.put("1996", 50000L);
        volumesPerYear.put("1997", 100000L);
        volumesPerYear.put("2008", 1000000L);
        service.setYearVolumes(new YearVolumes(volumesPerYear));
    }

    private static SearchQuery timelineQuery() {
        SearchQuery searchQuery = new SearchQueryImpl("eleições");
        searchQuery.setTimeline(true);
        return searchQuery;
    }

    @Test
    public void isLastPage() {
        SearchQuery searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setOffset(10);
        searchQuery.setMaxItems(10);
        assertThat(SolrSearchService.isLastPage(20, searchQuery)).isTrue();
        assertThat(SolrSearchService.isLastPage(21, searchQuery)).isFalse();
    }

    @Test
    public void applyTimeAllowed_defaultsTo60000ms() {
        assertThat(service.applyTimeAllowed(new SolrQuery()).get("timeAllowed")).isEqualTo("60000");
    }

    @Test
    public void applyTimeAllowed_isConfigurable() {
        SearchServiceConfiguration configuration = new SearchServiceConfiguration();
        configuration.setBaseSolrUrl("http://solr.example.com/solr/searchpages");
        configuration.setTimeAllowedMs(5000);
        SolrSearchService configuredService = new SolrSearchService(configuration);

        assertThat(configuredService.applyTimeAllowed(new SolrQuery()).get("timeAllowed")).isEqualTo("5000");
    }

    @Test
    public void convertSearchQuery_setsTimeAllowed() {
        SolrQuery solrQuery = service.convertSearchQuery(new SearchQueryImpl("sapo"));
        assertThat(solrQuery.get("timeAllowed")).isEqualTo("60000");
    }

    @Test
    public void sanitizeDedupField_defaultsToTitleStringForNullOrInvalid() {
        assertThat(service.sanitizeDedupField(null)).isEqualTo("titleString");
        assertThat(service.sanitizeDedupField("")).isEqualTo("titleString");
        assertThat(service.sanitizeDedupField("not-a-real-field")).isEqualTo("titleString");
        // "title" is a valid dedup field name but is explicitly redirected to "titleString" too
        assertThat(service.sanitizeDedupField("title")).isEqualTo("titleString");
    }

    @Test
    public void sanitizeDedupField_mapsSiteAndSurtToSurtOldest() {
        assertThat(service.sanitizeDedupField("site")).isEqualTo("surtOldest");
        assertThat(service.sanitizeDedupField("surt")).isEqualTo("surtOldest");
        // matching is case-insensitive
        assertThat(service.sanitizeDedupField("SITE")).isEqualTo("surtOldest");
    }

    @Test
    public void sanitizeDedupField_mapsUrlToSurtOldest() {
        // "url" is also the implicit dedupField PageSearchController sets for site-restricted searches that don't
        // specify one explicitly; it wasn't in the allowlist, so those requests silently deduped by title instead
        assertThat(service.sanitizeDedupField("url")).isEqualTo("surtOldest");
        assertThat(service.sanitizeDedupField("URL")).isEqualTo("surtOldest");
    }

    @Test
    public void sanitizeDedupField_mapsMimetypeToType() {
        assertThat(service.sanitizeDedupField("mimetype")).isEqualTo("type");
    }

    @Test
    public void sanitizeDedupField_mapsCollectionToCollectionOldest() {
        // "collection" isn't a real Solr field (only "collectionOldest" and the multi-valued "collections" are), so
        // passing it through unchanged made Solr reject the {!collapse field=collection} filter with a 500
        assertThat(service.sanitizeDedupField("collection")).isEqualTo("collectionOldest");
        // matching is case-insensitive
        assertThat(service.sanitizeDedupField("COLLECTION")).isEqualTo("collectionOldest");
    }

    @Test
    public void sanitizeDedupField_passesThroughOtherValidFieldsUnchanged() {
        assertThat(service.sanitizeDedupField("type")).isEqualTo("type");
        // Solr field names are case-sensitive, so passing them in directly must come back with their proper casing,
        // not lowercased like the rest of the input
        assertThat(service.sanitizeDedupField("collectionOldest")).isEqualTo("collectionOldest");
        assertThat(service.sanitizeDedupField("COLLECTIONOLDEST")).isEqualTo("collectionOldest");
        assertThat(service.sanitizeDedupField("surtOldest")).isEqualTo("surtOldest");
        assertThat(service.sanitizeDedupField("titleString")).isEqualTo("titleString");
    }

    @Test
    public void sanitizeQuery_preservesExactMatchQuotedPhrase() {
        SolrQuery solrQuery = new SolrQuery();
        String result = service.sanitizeQuery("\"hello world\"", solrQuery);
        // the phrase inside the quotes is still escaped like any other term (e.g. the space becomes "\ ")
        assertThat(result).isEqualTo("\"" + ClientUtils.escapeQueryChars("hello world") + "\"");
        assertThat(solrQuery.getFilterQueries()).isNull();
    }

    @Test
    public void sanitizeQuery_extractsExcludeTermsAsFilterQueries() {
        SolrQuery solrQuery = new SolrQuery();
        String result = service.sanitizeQuery("hello -world", solrQuery);
        assertThat(result).isEqualTo(ClientUtils.escapeQueryChars("hello "));
        assertThat(solrQuery.getFilterQueries()).containsExactlyInAnyOrder("-content:world", "-title:world");
    }

    @Test
    public void sanitizeQuery_escapesSpecialCharacters() {
        SolrQuery solrQuery = new SolrQuery();
        String result = service.sanitizeQuery("hello (world)", solrQuery);
        assertThat(result).isEqualTo(ClientUtils.escapeQueryChars("hello (world)"));
    }

    @Test
    public void convertSearchQuery_toleratesUnavailableShards() {
        SolrQuery solrQuery = service.convertSearchQuery(new SearchQueryImpl("sapo"));
        assertThat(solrQuery.get("shards.tolerant")).isEqualTo("true");
    }

    @Test
    public void convertSearchQuery_requiresAllTermsToMatch() {
        // Solr/edismax default to OR between terms, which only ever grows the result set as more terms are
        // added; the API forces AND so that additional terms narrow the search instead
        SolrQuery solrQuery = service.convertSearchQuery(new SearchQueryImpl("Lisboa Porto"));
        assertThat(solrQuery.get("q.op")).isEqualTo("AND");
    }

    @Test
    public void convertSearchQuery_neverServesBlockedDocuments() {
        SolrQuery solrQuery = service.convertSearchQuery(new SearchQueryImpl("sapo"));
        assertThat(solrQuery.getFilterQueries()).contains("-blocked:1");
    }

    @Test
    public void convertSearchQuery_defaultsToMatchAllWhenNoQueryTerms() {
        SearchQueryImpl searchQuery = new SearchQueryImpl(null);
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        assertThat(solrQuery.getQuery()).isEqualTo("*:*");
    }

    @Test
    public void convertSearchQuery_mapsPdfTypeToApplicationPdfMimetype() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setType(new String[] { "pdf" });
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        assertThat(solrQuery.getFilterQueries())
                .contains("type:" + ClientUtils.escapeQueryChars("application/pdf"));
    }

    @Test
    public void convertSearchQuery_mapsOfficeAliasTypeToTwoMimetypesWithOr() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setType(new String[] { "xls" });
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        String expected = "type:" + ClientUtils.escapeQueryChars("application/vnd.ms-excel")
                + " OR type:" + ClientUtils.escapeQueryChars("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(solrQuery.getFilterQueries()).contains(expected);
    }

    @Test
    public void convertSearchQuery_unknownTypeFallsBackToWildcardMatch() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setType(new String[] { "zzz" });
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        assertThat(solrQuery.getFilterQueries())
                .contains("type:*" + ClientUtils.escapeQueryChars("zzz") + "*");
    }

    @Test
    public void convertSearchQuery_explicitMimetypeWithSlashIsUsedAsIs() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setType(new String[] { "application/zip" });
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        assertThat(solrQuery.getFilterQueries())
                .contains("type:" + ClientUtils.escapeQueryChars("application/zip"));
    }

    @Test
    public void convertSearchQuery_groupsOnTheSanitizedDedupField() {
        // dedupField=collection used to be passed straight through to Solr as {!collapse field=collection}, but
        // "collection" isn't a real Solr field (only "collectionOldest" is), which made Solr reject the query
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setDedupField("collection");
        searchQuery.setDedupValue(2);
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        assertThat(solrQuery.getBool("group")).isTrue();
        assertThat(solrQuery.get("group.field")).isEqualTo("collectionOldest");
        assertThat(solrQuery.get("group.limit")).isEqualTo("2");
        // group.ngroups is deliberately not requested: computing an exact cross-shard distinct-group count is far
        // more expensive than the search itself (see arquivo/pwa-technologies#1624 performance follow-up)
        assertThat(solrQuery.get("group.ngroups")).isNull();
    }

    @Test
    public void convertSearchQuery_dedupValueZeroOrOneMeansOneResultPerGroup() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setDedupValue(0);
        assertThat(service.convertSearchQuery(searchQuery).get("group.limit")).isEqualTo("1");

        searchQuery.setDedupValue(1);
        assertThat(service.convertSearchQuery(searchQuery).get("group.limit")).isEqualTo("1");
    }

    @Test
    public void convertSearchQuery_dedupValueMinusOneDisablesGrouping() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setDedupValue(-1);
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        assertThat(solrQuery.get("group")).isNull();
        assertThat(solrQuery.get("group.field")).isNull();
        assertThat(solrQuery.get("group.limit")).isNull();
        assertThat(solrQuery.get("group.ngroups")).isNull();
    }

    @Test
    public void timelineServiceIsSharedByTheRequestsRacingOnAColdStart() throws Exception {
        assertThat(distinctFromRacingCallers(service::getTimelineService)).isEqualTo(1);
    }

    @Test
    public void yearVolumesAreSharedByTheRequestsRacingOnAColdStart() throws Exception {
        // Every request getting volumes of its own would mean a baseline query to Solr each, which is the very thing
        // the volumes cache. The ones the setUp injects would hide the race, so this starts from a cold service.
        SearchServiceConfiguration configuration = new SearchServiceConfiguration();
        configuration.setStartDate("19960101000000");
        configuration.setBaseSolrUrl("http://solr.example.com/solr/searchpages");
        SolrSearchService coldService = new SolrSearchService(configuration);

        assertThat(distinctFromRacingCallers(coldService::getYearVolumes)).isEqualTo(1);
    }

    /**
     * Calls a lazily initialized getter from many threads at once, and returns how many distinct instances it handed
     * out. One means the racing callers shared it.
     */
    private static <T> int distinctFromRacingCallers(Callable<T> getter) throws Exception {
        int racers = 16;
        CyclicBarrier startTogether = new CyclicBarrier(racers);
        ExecutorService threads = Executors.newFixedThreadPool(racers);

        List<Future<T>> results = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            results.add(threads.submit(() -> {
                startTogether.await();
                return getter.call();
            }));
        }

        Set<T> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Future<T> result : results) {
            distinct.add(result.get());
        }
        threads.shutdown();

        return distinct.size();
    }

    @Test
    public void convertTimelineQuery_toleratesUnavailableShards() {
        // convertTimelineQuery builds on top of convertSearchQuery, so it inherits shards.tolerant from there
        SolrQuery timelineQuery = service.convertTimelineQuery(timelineQuery());
        assertThat(timelineQuery.get("shards.tolerant")).isEqualTo("true");
    }

    @Test
    public void timelineFacetsByYear() {
        SolrQuery timelineQuery = service.convertTimelineQuery(timelineQuery());

        assertThat(timelineQuery.getBool("facet")).isTrue();
        assertThat(timelineQuery.get("facet.range")).isEqualTo("dateOldest");
        assertThat(timelineQuery.get("facet.range.gap")).isEqualTo("+1YEAR");
        // The whole archive is covered, from its first year up to the one running now
        assertThat(timelineQuery.get("facet.range.start")).isEqualTo("1996-01-01T00:00:00Z");
        assertThat(timelineQuery.get("facet.range.end"))
                .isEqualTo((Year.now().getValue() + 1) + "-01-01T00:00:00Z");
        // Years without matches still come back, the timeline is meant to show the gaps
        assertThat(timelineQuery.get("facet.mincount")).isEqualTo("0");
    }

    @Test
    public void timelineQueryAsksForNoDocuments() {
        SearchQuery searchQuery = timelineQuery();
        searchQuery.setOffset(50);
        searchQuery.setMaxItems(50);

        SolrQuery timelineQuery = service.convertTimelineQuery(searchQuery);

        assertThat(timelineQuery.getRows()).isEqualTo(0);
        assertThat(timelineQuery.getStart()).isEqualTo(0);
        assertThat(timelineQuery.get("hl")).isEqualTo("false");
        assertThat(timelineQuery.get("spellcheck")).isEqualTo("false");
    }

    @Test
    public void timelineQueryIsNotDeduplicated() {
        SearchQuery searchQuery = timelineQuery();

        // The search groups on the dedup field, which is a costly post filter and would leave the yearly counts no
        // longer comparable with the counts of the whole archive
        assertThat(service.convertSearchQuery(searchQuery).getBool("group")).isTrue();

        SolrQuery timelineQuery = service.convertTimelineQuery(searchQuery);
        assertThat(timelineQuery.getFilterQueries()).containsExactly("-blocked:1");
        assertThat(timelineQuery.get("group")).isNull();
        assertThat(timelineQuery.get("group.field")).isNull();
        assertThat(timelineQuery.get("group.limit")).isNull();
        assertThat(timelineQuery.get("group.ngroups")).isNull();
    }

    @Test
    public void timelineQueryKeepsTheSearchFilters() {
        SearchQuery searchQuery = timelineQuery();
        searchQuery.setType(new String[]{"pdf"});
        searchQuery.setCollection(new String[]{"AWP1"});

        SolrQuery timelineQuery = service.convertTimelineQuery(searchQuery);

        assertThat(timelineQuery.getQuery()).isEqualTo("eleições");
        assertThat(timelineQuery.getFilterQueries())
                .contains("type:application\\/pdf", "collections:AWP1");
        assertThat(timelineQuery.get("group")).isNull();
    }

    @Test
    public void collectionRequest_singleCollectionIsExactMatch() {
        SearchQuery searchQuery = new SearchQueryImpl("eleições");
        searchQuery.setCollection(new String[]{"FAWP1"});

        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);

        assertThat(solrQuery.getFilterQueries()).contains("collections:FAWP1");
    }

    @Test
    public void collectionRequest_multipleExactCollectionsEachRepeatTheFieldName() {
        SearchQuery searchQuery = new SearchQueryImpl("eleições");
        searchQuery.setCollection(new String[]{"FAWP1", "MAWP1"});

        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);

        // Each term must repeat "collections:", otherwise the second term is evaluated against the default query
        // field instead of "collections"
        assertThat(solrQuery.getFilterQueries()).contains("collections:FAWP1 OR collections:MAWP1");
    }

    @Test
    public void yearBalanceBoostsTheThinYears() {
        SearchQuery searchQuery = new SearchQueryImpl("eleições");
        searchQuery.setYearBalance(1.0);

        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);

        // 1996 is the thinnest year of this archive, so at full strength it is lifted by MAX_LIFT
        assertThat(solrQuery.get("boost")).contains("ms(dateOldest)").contains("1.2500");
    }

    @Test
    public void searchQueryIsNotBoostedWhenYearBalanceIsntAskedFor() {
        assertThat(service.convertSearchQuery(new SearchQueryImpl("eleições")).get("boost")).isNull();
    }

    @Test
    public void timelineQueryIsNotBoosted() {
        SearchQuery searchQuery = timelineQuery();
        searchQuery.setYearBalance(1.0);

        // Counting the documents of each year has no use for how they are scored
        assertThat(service.convertTimelineQuery(searchQuery).get("boost")).isNull();
    }

    @Test
    public void searchQueryIsNotFacetedWhenTheTimelineIsntAskedFor() {
        SolrQuery solrQuery = service.convertSearchQuery(new SearchQueryImpl("eleições"));

        assertThat(solrQuery.getBool("facet", false)).isFalse();
        assertThat(solrQuery.get("facet.range")).isNull();
    }

    @Test
    public void convertSearchQuery_filtersByLanguageAndConfidentDetectionsByDefault() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setLanguage("pt");
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        assertThat(solrQuery.getFilterQueries()).contains("language:pt", "languageConfidence:(HIGH)");
    }

    @Test
    public void convertSearchQuery_minLanguageConfidenceTakesEveryTierDownToTheOneAskedFor() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setLanguage("pt");

        searchQuery.setMinLanguageConfidence("MEDIUM");
        assertThat(service.convertSearchQuery(searchQuery).getFilterQueries())
                .contains("language:pt", "languageConfidence:(HIGH OR MEDIUM)");

        // the least confident tier is indexed as NONE
        searchQuery.setMinLanguageConfidence("LOW");
        assertThat(service.convertSearchQuery(searchQuery).getFilterQueries())
                .contains("languageConfidence:(HIGH OR MEDIUM OR NONE)");
    }

    @Test
    public void convertSearchQuery_isNotFilteredByConfidenceWhenItDoesNotAskAboutTheLanguage() {
        SolrQuery solrQuery = service.convertSearchQuery(new SearchQueryImpl("sapo"));
        assertThat(solrQuery.getFilterQueries()).noneMatch(filterQuery -> filterQuery.startsWith("language"));
    }

    @Test
    public void convertSearchQuery_asksSolrForTheLanguageFieldsOnlyWhenRequested() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        assertThat(service.convertSearchQuery(searchQuery).getFields()).doesNotContain("language");

        searchQuery.setFields(new String[] { "title", "language" });
        assertThat(service.convertSearchQuery(searchQuery).getFields())
                .contains("language")
                .doesNotContain("languageConfidence");

        searchQuery.setFields(new String[] { "title", "language", "languageConfidence" });
        assertThat(service.convertSearchQuery(searchQuery).getFields()).contains("languageConfidence");
    }

    @Test
    public void convertSearchQuery_alwaysRequestsTheUnifiedHighlightingMethod() {
        // fastVector (Solr's default highlighter) requires term vectors our index doesn't carry, so hl.method
        // is forced explicitly rather than left to Solr's server-side defaults (arquivo/pwa-technologies#1609)
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        assertThat(service.convertSearchQuery(searchQuery).get("hl.method")).isEqualTo("unified");

        // Still present even when highlighting itself is turned off for this query
        searchQuery.setFields(new String[] { "title" });
        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);
        assertThat(solrQuery.get("hl")).isEqualTo("false");
        assertThat(solrQuery.get("hl.method")).isEqualTo("unified");
    }

    @Test
    public void convertSearchQuery_setsHlFragsizeFromSnippetMaxLength() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setSnippetMaxLength(150);
        assertThat(service.convertSearchQuery(searchQuery).getInt("hl.fragsize", -1)).isEqualTo(150);
    }

    @Test
    public void convertSearchQuery_usesWordBoundaryScannerSoFragsizeIsActuallyHonored() {
        // hl.fragsize is only a hint to the Unified Highlighter: its default SENTENCE boundary scanner can treat a
        // whole run of unpunctuated text (common in scraped web content) as a single "sentence" and ignore fragsize
        // entirely (arquivo/pwa-technologies#1635). A WORD boundary scanner keeps it honoring fragsize instead.
        SolrQuery solrQuery = service.convertSearchQuery(new SearchQueryImpl("sapo"));
        assertThat(solrQuery.get("hl.bs.type")).isEqualTo("WORD");
        assertThat(solrQuery.get("hl.fragsizeIsMinimum")).isEqualTo("false");
    }

    @Test
    public void convertSearchQuery_doesNotSetFragmentSizingParamsWhenSnippetNotNeeded() {
        // hl.fragsize/hl.fragsizeIsMinimum/hl.bs.type only matter to the highlighter that builds the snippet, so
        // there's nothing for them to size when the query doesn't ask for one
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setFields(new String[] { "title" });

        SolrQuery solrQuery = service.convertSearchQuery(searchQuery);

        assertThat(solrQuery.get("hl.fragsize")).isNull();
        assertThat(solrQuery.get("hl.fragsizeIsMinimum")).isNull();
        assertThat(solrQuery.get("hl.bs.type")).isNull();
    }

    @Test
    public void timestampSurtTo_extractsCollectionTimestampAndSurt() {
        String urlTimestamp = "COLLECTION1/20190101000000/(com,example,)/path";
        assertThat(service.timestampSurtToCollection(urlTimestamp)).isEqualTo("COLLECTION1");
        assertThat(service.timestampSurtToTimestamp(urlTimestamp)).isEqualTo("20190101000000");
        assertThat(service.timestampSurtToSurt(urlTimestamp)).isEqualTo("(com,example,)/path");
    }

    @Test
    public void filterUrlTimestamps_dropsEntriesWithoutSlash() {
        List<Object> urlstimestamps = new ArrayList<>(Arrays.asList("malformed-entry-no-slash"));
        List<Object> result = service.filterUrlTimestamps(urlstimestamps, null, null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    public void filterUrlTimestamps_filtersBySiteSearchSurtPrefix() {
        List<Object> urlstimestamps = new ArrayList<>(Arrays.asList(
                "COLLECTION1/20190101000000/(com,example,)/path1",
                "COLLECTION1/20190101000000/(com,other,)/path2"));
        List<Object> result = service.filterUrlTimestamps(urlstimestamps, null, null,
                new String[] { "(com,example,)" }, null);
        assertThat(result).containsExactly("COLLECTION1/20190101000000/(com,example,)/path1");
    }

    @Test
    public void filterUrlTimestamps_filtersByTimeRange() {
        List<Object> urlstimestamps = new ArrayList<>(Arrays.asList(
                "COLLECTION1/20180101000000/(com,example,)/path1",
                "COLLECTION1/20190101000000/(com,example,)/path2",
                "COLLECTION1/20200101000000/(com,example,)/path3"));
        List<Object> result = service.filterUrlTimestamps(urlstimestamps, 20191231000000L, 20190101000000L, null, null);
        assertThat(result).containsExactly("COLLECTION1/20190101000000/(com,example,)/path2");
    }

    @Test
    public void filterUrlTimestamps_filtersByCollection() {
        List<Object> urlstimestamps = new ArrayList<>(Arrays.asList(
                "COLLECTION1/20190101000000/(com,example,)/path1",
                "COLLECTION2/20190101000000/(com,example,)/path2"));
        List<Object> result = service.filterUrlTimestamps(urlstimestamps, null, null, null,
                new String[] { "COLLECTION1" });
        assertThat(result).containsExactly("COLLECTION1/20190101000000/(com,example,)/path1");
    }

    @Test
    public void getOldestUrlTimestamp_returnsEntryWithSmallestTimestamp() {
        List<Object> urlstimestamps = new ArrayList<>(Arrays.asList(
                "COLLECTION1/20200101000000/(com,example,)/path1",
                "COLLECTION1/20180101000000/(com,example,)/path2",
                "COLLECTION1/20190101000000/(com,example,)/path3"));
        assertThat(service.getOldestUrlTimestamp(urlstimestamps))
                .isEqualTo("COLLECTION1/20180101000000/(com,example,)/path2");
    }

    private static QueryResponse queryResponseWithCollation(String collation) {
        NamedList<Object> collations = new NamedList<>();
        if (collation != null) {
            collations.add("collation", collation);
        }
        NamedList<Object> spellcheck = new NamedList<>();
        // SpellCheckResponse bails out before reading "collations" unless "suggestions" is present too
        spellcheck.add("suggestions", new NamedList<>());
        spellcheck.add("collations", collations);
        NamedList<Object> response = new NamedList<>();
        response.add("spellcheck", spellcheck);

        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(response);
        return queryResponse;
    }

    @Test
    public void parseSuggestedQuery_returnsNullWhenNoSpellCheckResponse() {
        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(new NamedList<>());
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        assertThat(service.parseSuggestedQuery(queryResponse, searchQuery)).isNull();
    }

    @Test
    public void parseSuggestedQuery_returnsNullWhenCollationMatchesOriginalQuery() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("hello");
        QueryResponse queryResponse = queryResponseWithCollation("\"hello\"");
        assertThat(service.parseSuggestedQuery(queryResponse, searchQuery)).isNull();
    }

    @Test
    public void parseSuggestedQuery_stripsQuotesWeAddedOurselves() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("origanal query");
        QueryResponse queryResponse = queryResponseWithCollation("\"original query\"");
        assertThat(service.parseSuggestedQuery(queryResponse, searchQuery)).isEqualTo("original query");
    }

    @Test
    public void parseSuggestedQuery_doesNotStripQuotesUserAlreadyProvided() {
        // queryTerms is already quoted by the user, so getQuotedQueryTerms() == queryTerms and no quotes were added by us
        SearchQueryImpl searchQuery = new SearchQueryImpl("\"origanal query\"");
        QueryResponse queryResponse = queryResponseWithCollation("\"original query\"");
        assertThat(service.parseSuggestedQuery(queryResponse, searchQuery)).isEqualTo("\"original query\"");
    }

    private static SolrDocument docWithUrlTimestamp(String id, String urlTimestamp) {
        SolrDocument doc = new SolrDocument();
        doc.addField("id", id);
        doc.addField("urlTimestamp", urlTimestamp);
        return doc;
    }

    private static QueryResponse queryResponseWithResults(SolrDocument... docs) {
        SolrDocumentList docList = new SolrDocumentList();
        docList.addAll(Arrays.asList(docs));
        docList.setNumFound(docs.length);

        NamedList<Object> response = new NamedList<>();
        response.add("response", docList);
        // Highlighting is on by default in Solr whenever snippets are requested (see convertSearchQuery),
        // so a real response always carries a "highlighting" key, even if empty for every doc.
        response.add("highlighting", new NamedList<>());

        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(response);
        return queryResponse;
    }

    private static QueryResponse queryResponseWithHighlighting(SolrDocument doc, String fieldName, String snippet) {
        return queryResponseWithHighlighting(doc, fieldName, Arrays.asList(snippet));
    }

    private static QueryResponse queryResponseWithHighlighting(SolrDocument doc, String fieldName, List<String> snippets) {
        NamedList<List<String>> docHighlight = new NamedList<>();
        docHighlight.add(fieldName, snippets);
        NamedList<Object> highlighting = new NamedList<>();
        highlighting.add((String) doc.getFieldValue("id"), docHighlight);

        SolrDocumentList docList = new SolrDocumentList();
        docList.add(doc);
        docList.setNumFound(1);

        NamedList<Object> response = new NamedList<>();
        response.add("response", docList);
        response.add("highlighting", highlighting);

        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(response);
        return queryResponse;
    }

    @Test
    public void query_happyPath_mapsSolrDocumentToSearchResult() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        SearchResults results = service.query(searchQuery);

        assertThat(results.getEstimatedNumberResults()).isEqualTo(1);
        assertThat(results.getNumberResults()).isEqualTo(1);
        assertThat(results.isLastPageResults()).isTrue();
        assertThat(results.getResults()).hasSize(1);
        assertThat(results.getResults().get(0).getId()).isEqualTo("doc-1");
        assertThat(results.getResults().get(0).getCollection()).isEqualTo("COLLECTION1");
        assertThat(results.getResults().get(0).getTstamp()).isEqualTo("20190101010101");
    }

    @Test
    public void query_propagatesTimeAllowedToEachSearchResult() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        SearchResults results = service.query(new SearchQueryImpl("sapo"));

        SearchResultSolrImpl result = (SearchResultSolrImpl) results.getResults().get(0);
        assertThat(result.getTimeAllowed()).isEqualTo(60000);
    }

    @Test
    public void query_solrServerException_returnsEmptyFallbackResults() throws Exception {        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenThrow(new SolrServerException("boom"));
        service.solrClient = solrClient;

        SearchResults results = service.query(new SearchQueryImpl("sapo"));

        assertThat(results.getEstimatedNumberResults()).isEqualTo(0);
        assertThat(results.getNumberResults()).isEqualTo(0);
        assertThat(results.isLastPageResults()).isFalse();
        assertThat(results.getResults()).isNull();
    }

    @Test
    public void query_setsTimeAllowedOnSolrRequest() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        // Restricted to a field that isn't the snippet, so this doesn't also trigger the highlighting fallback query
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setFields(new String[] { "title" });
        service.query(searchQuery);

        ArgumentCaptor<SolrQuery> solrQueryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(solrQueryCaptor.capture());
        assertThat(solrQueryCaptor.getValue().get("timeAllowed")).isEqualTo("60000");
    }

    @Test
    public void query_truncatesTitleToDefaultMaxLengthWithEllipsis() throws Exception {
        String longTitle = StringUtils.repeat("a", 310);
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        doc.addField("titleString", longTitle);
        QueryResponse queryResponse = queryResponseWithResults(doc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setFields(new String[] { "title" });
        SearchResults results = service.query(searchQuery);

        String title = results.getResults().get(0).getTitle();
        assertThat(title).isEqualTo(StringUtils.repeat("a", 300) + "…");
    }

    @Test
    public void query_respectsCustomTitleMaxLength() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        doc.addField("titleString", "abcdefghij");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setFields(new String[] { "title" });
        searchQuery.setTitleMaxLength(5);
        SearchResults results = service.query(searchQuery);

        assertThat(results.getResults().get(0).getTitle()).isEqualTo("abcde…");
    }

    @Test
    public void query_titleMaxLengthZeroDisablesTruncation() throws Exception {
        String longTitle = StringUtils.repeat("a", 310);
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        doc.addField("titleString", longTitle);
        QueryResponse queryResponse = queryResponseWithResults(doc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setFields(new String[] { "title" });
        searchQuery.setTitleMaxLength(0);
        SearchResults results = service.query(searchQuery);

        assertThat(results.getResults().get(0).getTitle()).isEqualTo(longTitle);
    }

    @Test
    public void query_doesNotAppendEllipsisWhenTitleShorterThanLimit() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        doc.addField("titleString", "short title");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        searchQuery.setFields(new String[] { "title" });
        SearchResults results = service.query(searchQuery);

        assertThat(results.getResults().get(0).getTitle()).isEqualTo("short title");
    }

    @Test
    public void getHighlightedText_usesHighlightSnippetWhenPresent() {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithHighlighting(doc, "content", "hi <em>there</em>");

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 300);

        assertThat(highlighted).isEqualTo("hi <em>there</em><span class=\"ellipsis\"> ... </span>");
    }

    @Test
    public void getHighlightedText_capsHighlightedSnippetEvenWhenSolrIgnoresFragsize() {
        // Guards against the Unified Highlighter returning something longer than hl.fragsize asked for (see
        // arquivo/pwa-technologies#1635) - the API must enforce snippetMaxLength itself regardless.
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        String longSnippet = "hi " + StringUtils.repeat("a", 300) + " <em>there</em>";
        QueryResponse queryResponse = queryResponseWithHighlighting(doc, "content", longSnippet);

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 10);

        assertThat(highlighted).isEqualTo("hi aaaaaaa<span class=\"ellipsis\"> ... </span>");
    }

    @Test
    public void getHighlightedText_truncationClosesADanglingEmTagInsteadOfCuttingMidTag() {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        // The match starts right before the cutoff, so a naive substring(0, 10) would land inside "<em>"
        QueryResponse queryResponse = queryResponseWithHighlighting(doc, "content", "0123456789<em>match</em> more text");

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 12);

        assertThat(highlighted).isEqualTo("0123456789<span class=\"ellipsis\"> ... </span>");
    }

    @Test
    public void getHighlightedText_truncationClosesAStillOpenEmTag() {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithHighlighting(doc, "content", "before <em>matched term</em> after");

        // Cuts in the middle of the highlighted term itself, leaving "<em>" open
        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 15);

        assertThat(highlighted).isEqualTo("before <em>matc</em><span class=\"ellipsis\"> ... </span>");
    }

    @Test
    public void getHighlightedText_snippetMaxLengthZeroDisablesTruncationOfHighlightedSnippet() {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        String longSnippet = "hi " + StringUtils.repeat("a", 300) + " <em>there</em>";
        QueryResponse queryResponse = queryResponseWithHighlighting(doc, "content", longSnippet);

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 0);

        assertThat(highlighted).isEqualTo(longSnippet + "<span class=\"ellipsis\"> ... </span>");
    }

    @Test
    public void getHighlightedText_truncatesEachFragmentIndependentlyWhenMultipleSnippetsReturned() {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithHighlighting(doc, "content",
                Arrays.asList("<em>first</em> match here", "<em>second</em> match here"));

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 10);

        assertThat(highlighted).isEqualTo("<em>first</em><span class=\"ellipsis\"> ... </span>"
                + "<em>second</em><span class=\"ellipsis\"> ... </span>");
    }

    @Test
    public void getHighlightedText_fallsBackToContentFieldWhenNoHighlight() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        SolrDocument contentDoc = new SolrDocument();
        contentDoc.addField("content", "short content");
        QueryResponse contentResponse = queryResponseWithResults(contentDoc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(contentResponse);
        service.solrClient = solrClient;

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 300);

        assertThat(highlighted).isEqualTo("short content");
    }

    @Test
    public void getHighlightedText_fallbackQueryToleratesUnavailableShards() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        SolrDocument contentDoc = new SolrDocument();
        contentDoc.addField("content", "short content");
        QueryResponse contentResponse = queryResponseWithResults(contentDoc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(contentResponse);
        service.solrClient = solrClient;

        service.getHighlightedText(queryResponse, "content", "doc-1", 300);

        ArgumentCaptor<SolrQuery> solrQueryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(solrQueryCaptor.capture());
        assertThat(solrQueryCaptor.getValue().get("shards.tolerant")).isEqualTo("true");
    }

    @Test
    public void getHighlightedText_truncatesLongContentFallbackWithEllipsis() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        String longContent = String.join("", java.util.Collections.nCopies(600, "a"));
        SolrDocument contentDoc = new SolrDocument();
        contentDoc.addField("content", longContent);
        QueryResponse contentResponse = queryResponseWithResults(contentDoc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(contentResponse);
        service.solrClient = solrClient;

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 500);

        assertThat(highlighted).isEqualTo(longContent.substring(0, 500) + "<span class=\"ellipsis\"> ... </span>");
    }

    @Test
    public void getHighlightedText_fallbackRespectsCustomSnippetMaxLength() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        SolrDocument contentDoc = new SolrDocument();
        contentDoc.addField("content", "abcdefghij");
        QueryResponse contentResponse = queryResponseWithResults(contentDoc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(contentResponse);
        service.solrClient = solrClient;

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 5);

        assertThat(highlighted).isEqualTo("abcde<span class=\"ellipsis\"> ... </span>");
    }

    @Test
    public void getHighlightedText_fallbackSnippetMaxLengthZeroDisablesTruncation() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        String longContent = String.join("", java.util.Collections.nCopies(600, "a"));
        SolrDocument contentDoc = new SolrDocument();
        contentDoc.addField("content", longContent);
        QueryResponse contentResponse = queryResponseWithResults(contentDoc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(contentResponse);
        service.solrClient = solrClient;

        String highlighted = service.getHighlightedText(queryResponse, "content", "doc-1", 0);

        assertThat(highlighted).isEqualTo(longContent);
    }

    @Test
    public void getHighlightedText_fallbackSetsTimeAllowedOnSolrRequest() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);
        QueryResponse contentResponse = queryResponseWithResults(new SolrDocument());

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(contentResponse);
        service.solrClient = solrClient;

        service.getHighlightedText(queryResponse, "content", "doc-1", 300);

        ArgumentCaptor<SolrQuery> solrQueryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(solrQueryCaptor.capture());
        assertThat(solrQueryCaptor.getValue().get("timeAllowed")).isEqualTo("60000");
    }

    @Test
    public void query_urlSearch_true_queriesByUrlTimestampAndExcludesSnippet() throws Exception {
        SolrDocument doc = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101000000/(com,example,)/path");
        QueryResponse queryResponse = queryResponseWithResults(doc);

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        SearchQueryImpl searchQuery = new SearchQueryImpl("http://example.com");
        searchQuery.setFrom("20190101000000");

        SearchResults results = service.query(searchQuery, true);

        ArgumentCaptor<SolrQuery> solrQueryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(solrQueryCaptor.capture());
        assertThat(solrQueryCaptor.getValue().getQuery()).startsWith("urlTimestamp:*/20190101000000/");
        assertThat(solrQueryCaptor.getValue().get("shards.tolerant")).isEqualTo("true");
        assertThat(solrQueryCaptor.getValue().get("timeAllowed")).isEqualTo("60000");
        assertThat(solrQueryCaptor.getValue().getFilterQueries()).contains("-blocked:1");

        assertThat(results.getResults()).hasSize(1);
        assertThat(((SearchResultSolrImpl) results.getResults().get(0)).getSnippet()).isNull();
    }

    /**
     * Builds a grouped QueryResponse matching the exact "grouped" NamedList shape solrj expects (confirmed by
     * reading QueryResponse#extractGroupedInfo): field -&gt; {matches, groups: [{groupValue, doclist}, ...]}.
     * ngroups is deliberately never included: group.ngroups isn't requested (see convertSearchQuery), since exact
     * cross-shard distinct-group counting is far more expensive than the search itself.
     */
    private static QueryResponse queryResponseWithGroups(String groupField, int matches, List<SolrDocument[]> groups) {
        ArrayList<Object> groupsArr = new ArrayList<>();
        for (SolrDocument[] groupDocs : groups) {
            SolrDocumentList doclist = new SolrDocumentList();
            doclist.addAll(Arrays.asList(groupDocs));
            doclist.setNumFound(groupDocs.length);

            SimpleOrderedMap<Object> grpMap = new SimpleOrderedMap<>();
            grpMap.add("groupValue", groupDocs.length > 0 ? groupDocs[0].getFieldValue("id") : null);
            grpMap.add("doclist", doclist);
            groupsArr.add(grpMap);
        }

        SimpleOrderedMap<Object> fieldGroups = new SimpleOrderedMap<>();
        fieldGroups.add("matches", matches);
        fieldGroups.add("groups", groupsArr);

        NamedList<Object> grouped = new NamedList<>();
        grouped.add(groupField, fieldGroups);

        NamedList<Object> response = new NamedList<>();
        response.add("grouped", grouped);
        response.add("highlighting", new NamedList<>());
        // No highlighting snippet is set on the test docs, so getHighlightedText falls back to a second Solr query
        // for the raw content, made through the same mocked client/response; give it an empty (but non-null)
        // "response" doclist to satisfy that fallback path, since a real grouped response wouldn't hit it either
        // (the docs would normally carry snippet fields already).
        SolrDocumentList emptyDocList = new SolrDocumentList();
        emptyDocList.setNumFound(0);
        response.add("response", emptyDocList);

        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(response);
        return queryResponse;
    }

    @Test
    public void query_groupedResponse_flattensGroupsInOrderAndUsesMatchesForEstimatedCount() throws Exception {
        SolrDocument doc1 = docWithUrlTimestamp("doc-1", "COLLECTION1/20190101010101/(com,example,)/path1");
        SolrDocument doc2 = docWithUrlTimestamp("doc-2", "COLLECTION1/20190101010101/(com,other,)/path2");
        SolrDocument doc3 = docWithUrlTimestamp("doc-3", "COLLECTION1/20190101010101/(com,third,)/path3");
        QueryResponse queryResponse = queryResponseWithGroups("titleOldest", 42,
                Arrays.asList(new SolrDocument[]{doc1}, new SolrDocument[]{doc2, doc3}));

        HttpSolrClient solrClient = mock(HttpSolrClient.class);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        service.solrClient = solrClient;

        SearchResults results = service.query(new SearchQueryImpl("sapo"));

        assertThat(results.getEstimatedNumberResults()).isEqualTo(42);
        assertThat(results.getNumberResults()).isEqualTo(2);
        assertThat(results.getResults()).extracting(SearchResult::getId)
                .containsExactly("doc-1", "doc-2", "doc-3");
    }

    private static SearchResultSolrImpl resultWithHostKey(String id, String hostKey) {
        SearchResultSolrImpl result = new SearchResultSolrImpl();
        result.setId(id);
        result.setHostKey(hostKey);
        return result;
    }

    @Test
    public void diversifyByHost_defersResultsPastTheCapToTheEndKeepingTheirRelativeOrder() {
        List<SearchResult> results = Arrays.asList(
                resultWithHostKey("a1", "hostA"),
                resultWithHostKey("a2", "hostA"),
                resultWithHostKey("b1", "hostB"),
                resultWithHostKey("a3", "hostA"),
                resultWithHostKey("a4", "hostA"),
                resultWithHostKey("b2", "hostB"),
                resultWithHostKey("a5", "hostA")
        );

        List<SearchResult> diversified = service.diversifyByHost(results);

        // hostA hits its cap (3) at a3; a4 and a5 get pushed after every other result, in their original order
        assertThat(diversified).extracting(SearchResult::getId)
                .containsExactly("a1", "a2", "b1", "a3", "b2", "a4", "a5");
    }

    @Test
    public void diversifyByHost_underTheCapKeepsOriginalOrder() {
        List<SearchResult> results = Arrays.asList(
                resultWithHostKey("a1", "hostA"),
                resultWithHostKey("b1", "hostB"),
                resultWithHostKey("a2", "hostA")
        );

        assertThat(service.diversifyByHost(results)).extracting(SearchResult::getId)
                .containsExactly("a1", "b1", "a2");
    }

    @Test
    public void diversifyByHost_neverDefersResultsWithoutAHostKey() {
        List<SearchResult> results = Arrays.asList(
                resultWithHostKey("a1", "hostA"),
                resultWithHostKey("a2", "hostA"),
                resultWithHostKey("a3", "hostA"),
                resultWithHostKey("a4", "hostA"),
                resultWithHostKey("none", null)
        );

        assertThat(service.diversifyByHost(results)).extracting(SearchResult::getId)
                .containsExactly("a1", "a2", "a3", "none", "a4");
    }
}
