package pt.arquivo.api;

import org.junit.Before;
import org.junit.Test;
import pt.arquivo.services.SearchResult;
import pt.arquivo.services.SearchResultNutchImpl;
import pt.arquivo.services.SearchResults;
import pt.arquivo.services.cdx.CDXSearchService;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MetadataControllerTest {

    private MetadataController metadataController;
    private PageSearchController pageSearchController;
    private CDXSearchService cdxSearchService;

    @Before
    public void setUp() {
        metadataController = new MetadataController();
        pageSearchController = mock(PageSearchController.class);
        cdxSearchService = mock(CDXSearchService.class);
        metadataController.pageSearchController = pageSearchController;
        metadataController.cdxSearchService = cdxSearchService;
    }

    private static SearchResults emptyResults() {
        SearchResults results = new SearchResults();
        results.setNumberResults(0);
        results.setResults(new ArrayList<>());
        return results;
    }

    private static SearchResults resultsWith(SearchResult... items) {
        SearchResults results = new SearchResults();
        ArrayList<SearchResult> list = new ArrayList<>();
        for (SearchResult item : items) {
            list.add(item);
        }
        results.setNumberResults(list.size());
        results.setResults(list);
        return results;
    }

    @Test
    public void getMetadata_idWithoutSlash_returnsEmptyResponse() {
        MetadataResponse response = metadataController.getMetadata("no-slash-id");

        assertThat(response.getResponseItems()).isNull();
    }

    @Test
    public void getMetadata_invalidTimestamp_returnsEmptyResponse() {
        MetadataResponse response = metadataController.getMetadata("http://example.com/20190101000000abc");

        assertThat(response.getResponseItems()).isNull();
    }

    @Test
    public void getMetadata_noTextSearchHit_usesCdxResults() {
        when(pageSearchController.queryByUrl(any())).thenReturn(emptyResults());
        SearchResultNutchImpl cdxResult = new SearchResultNutchImpl();
        when(cdxSearchService.getResults(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(resultsWith(cdxResult));

        MetadataResponse response = metadataController.getMetadata("http://example.com/20190101000000");

        assertThat(response.getResponseItems()).containsExactly(cdxResult);
    }

    @Test
    public void getMetadata_textSearchHitAndEmptyCdxResults_skipsEnrichment() {
        SearchResultNutchImpl textResult = new SearchResultNutchImpl();
        when(pageSearchController.queryByUrl(any())).thenReturn(resultsWith(textResult));
        when(cdxSearchService.getResults(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(emptyResults());

        MetadataResponse response = metadataController.getMetadata("http://example.com/20190101000000");

        assertThat(response.getResponseItems()).containsExactly(textResult);
        assertThat(textResult.getCollection()).isNull();
    }

    @Test
    public void getMetadata_textSearchHitAndNullCollection_enrichesWithoutSettingCollection() {
        SearchResultNutchImpl textResult = new SearchResultNutchImpl();
        SearchResultNutchImpl cdxResult = new SearchResultNutchImpl();
        cdxResult.setCollection(null);
        when(pageSearchController.queryByUrl(any())).thenReturn(resultsWith(textResult));
        when(cdxSearchService.getResults(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(resultsWith(cdxResult));

        MetadataResponse response = metadataController.getMetadata("http://example.com/20190101000000");

        assertThat(response.getResponseItems()).containsExactly(textResult);
        assertThat(textResult.getCollection()).isNull();
    }

    @Test
    public void getMetadata_textSearchHitAndEmptyCollection_doesNotOverwriteCollection() {
        SearchResultNutchImpl textResult = new SearchResultNutchImpl();
        SearchResultNutchImpl cdxResult = new SearchResultNutchImpl();
        cdxResult.setCollection("");
        when(pageSearchController.queryByUrl(any())).thenReturn(resultsWith(textResult));
        when(cdxSearchService.getResults(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(resultsWith(cdxResult));

        MetadataResponse response = metadataController.getMetadata("http://example.com/20190101000000");

        assertThat(textResult.getCollection()).isNull();
    }

    @Test
    public void getMetadata_textSearchHitAndNonEmptyCollection_enrichesTextResult() {
        SearchResultNutchImpl textResult = new SearchResultNutchImpl();
        SearchResultNutchImpl cdxResult = new SearchResultNutchImpl();
        cdxResult.setCollection("COLLECTION1");
        cdxResult.setStatusCode(200);
        cdxResult.setFileName("some-file.warc.gz");
        cdxResult.setDigest("DIGEST123");
        when(pageSearchController.queryByUrl(any())).thenReturn(resultsWith(textResult));
        when(cdxSearchService.getResults(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(resultsWith(cdxResult));

        MetadataResponse response = metadataController.getMetadata("http://example.com/20190101000000");

        assertThat(response.getResponseItems()).containsExactly(textResult);
        assertThat(textResult.getCollection()).isEqualTo("COLLECTION1");
        assertThat(textResult.getStatusCode()).isEqualTo(200);
        assertThat(textResult.getFileName()).isEqualTo("some-file.warc.gz");
        assertThat(textResult.getDigest()).isEqualTo("DIGEST123");
    }
}
