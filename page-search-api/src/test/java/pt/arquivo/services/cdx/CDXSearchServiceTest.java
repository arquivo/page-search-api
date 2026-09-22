package pt.arquivo.services.cdx;

import org.junit.Before;
import org.junit.Test;
import pt.arquivo.services.SearchResultNutchImpl;
import pt.arquivo.services.SearchResults;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class CDXSearchServiceTest {

    private CDXSearchService cdxSearchService;

    @Before
    public void setUp() {
        cdxSearchService = new CDXSearchService();
        cdxSearchService.waybackCdxEndpoint = "http://wayback.example.com/cdx";
        cdxSearchService.waybackServiceEndpoint = "http://wayback.example.com/wayback";
        cdxSearchService.waybackNoFrameServiceEndpoint = "http://wayback.example.com/noFrame";
        cdxSearchService.screenshotServiceEndpoint = "http://screenshot.example.com";
        cdxSearchService.textSearchServiceEndpoint = "http://textsearch.example.com";
        cdxSearchService.extractedTextServiceEndpoint = "http://extractedtext.example.com";
    }

    @Test
    public void getSearchResultNutch_mapsAllFields() {
        ItemCDX itemCDX = new ItemCDX("http://example.com", "201901010101203401", "DIGEST123",
                "text/html", "200", "some-file.warc.gz", "80", "1234", "COLLECTION1");

        SearchResultNutchImpl result = CDXSearchService.getSearchResultNutch(itemCDX);

        assertThat(result.getFileName()).isEqualTo("some-file.warc.gz");
        assertThat(result.getOffset()).isEqualTo(1234L);
        assertThat(result.getContentLength()).isEqualTo(80L);
        assertThat(result.getDigest()).isEqualTo("DIGEST123");
        assertThat(result.getMimeType()).isEqualTo("text/html");
        assertThat(result.getTstamp()).isEqualTo("201901010101203401");
        assertThat(result.getOriginalURL()).isEqualTo("http://example.com");
        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getCollection()).isEqualTo("COLLECTION1");
    }

    @Test
    public void getSearchResultNutch_toleratesNullLengthAndStatus() {
        ItemCDX itemCDX = new ItemCDX("http://example.com", "201901010101203401", "DIGEST123",
                "text/html", null, "some-file.warc.gz", null, "1234", "COLLECTION1");

        SearchResultNutchImpl result = CDXSearchService.getSearchResultNutch(itemCDX);

        assertThat(result.getContentLength()).isEqualTo(0L);
        assertThat(result.getStatusCode()).isNull();
    }

    @Test
    public void getSearchResultNutch_throwsOnMalformedOffset() {
        // Documents current fragile behavior: a malformed CDX "offset" field blows up the whole mapping
        ItemCDX itemCDX = new ItemCDX("http://example.com", "201901010101203401", "DIGEST123",
                "text/html", "200", "some-file.warc.gz", "80", "not-a-number", "COLLECTION1");

        assertThatThrownBy(() -> CDXSearchService.getSearchResultNutch(itemCDX))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    public void generateCdxQuery_buildsUrlWithEncodedQueryAndFromTo() {
        String query = cdxSearchService.generateCdxQuery("http://example.com/a b", "20190101000000", "20200101000000");

        assertThat(query).isEqualTo("http://wayback.example.com/cdx?url=http%3A%2F%2Fexample.com%2Fa+b"
                + "&output=json&from=20190101000000&to=20200101000000&reverse=true");
    }

    @Test
    public void generateCdxQuery_treatsNullFromAndToAsEmpty() {
        String query = cdxSearchService.generateCdxQuery("http://example.com", null, null);

        assertThat(query).isEqualTo("http://wayback.example.com/cdx?url=http%3A%2F%2Fexample.com"
                + "&output=json&from=&to=&reverse=true");
    }

    @Test
    public void populateEndpointsLinks_withoutTextMatch_doesNotSetExtractedTextLink() throws Exception {
        SearchResultNutchImpl result = new SearchResultNutchImpl();
        result.setTimeStamp("201901010101203401");
        result.setOriginalURL("http://example.com");

        cdxSearchService.populateEndpointsLinks(result, false);

        assertThat(result.getLinkToArchive()).isEqualTo("http://wayback.example.com/wayback/201901010101203401/http://example.com");
        assertThat(result.getLinkToNoFrame()).isEqualTo("http://wayback.example.com/noFrame/201901010101203401/http://example.com");
        assertThat(result.getLinkToScreenshot()).isEqualTo("http://screenshot.example.com?url="
                + "http%3A%2F%2Fwayback.example.com%2FnoFrame%2F201901010101203401%2Fhttp%3A%2F%2Fexample.com");
        assertThat(result.getLinkToOriginalFile()).isEqualTo("http://wayback.example.com/noFrame/201901010101203401id_/http://example.com");
        assertThat(result.getLinkToMetadata()).isEqualTo("http://textsearch.example.com?metadata="
                + "http%3A%2F%2Fexample.com%2F201901010101203401");
        assertThat(result.getLinkToExtractedText()).isNull();
    }

    @Test
    public void populateEndpointsLinks_withTextMatch_setsExtractedTextLink() throws Exception {
        SearchResultNutchImpl result = new SearchResultNutchImpl();
        result.setTimeStamp("201901010101203401");
        result.setOriginalURL("http://example.com");

        cdxSearchService.populateEndpointsLinks(result, true);

        assertThat(result.getLinkToExtractedText()).isEqualTo("http://extractedtext.example.com?m="
                + "http%3A%2F%2Fexample.com%2F201901010101203401");
    }

    private static URLConnection connectionReturning(String body) throws IOException {
        URLConnection connection = mock(URLConnection.class);
        when(connection.getInputStream()).thenReturn(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return connection;
    }

    @Test
    public void getResults_happyPath_mapsCdxJsonToSearchResults() throws Exception {
        String cdxJson = "{\"url\":\"http://example.com\",\"timestamp\":\"20190101010101\","
                + "\"digest\":\"DIGEST123\",\"mime\":\"text/html\",\"status\":\"200\","
                + "\"filename\":\"some-file.warc.gz\",\"length\":\"80\",\"offset\":\"1234\","
                + "\"collection\":\"COLLECTION1\"}\n";

        CDXSearchService spy = spy(cdxSearchService);
        doReturn(connectionReturning(cdxJson)).when(spy).openCdxConnection(anyString());

        SearchResults results = spy.getResults("http://example.com", null, null, 10, 0);

        assertThat(results.getEstimatedNumberResults()).isEqualTo(1);
        assertThat(results.getResults()).hasSize(1);
        SearchResultNutchImpl result = (SearchResultNutchImpl) results.getResults().get(0);
        assertThat(result.getOriginalURL()).isEqualTo("http://example.com");
        assertThat(result.getTitle()).isEqualTo("http://example.com");
        assertThat(result.getTstamp()).isEqualTo("20190101010101");
        assertThat(result.getCollection()).isEqualTo("COLLECTION1");
        assertThat(result.getLinkToArchive()).isEqualTo("http://wayback.example.com/wayback/20190101010101/http://example.com");
    }

    @Test
    public void getResults_connectionThrows_returnsZeroResults() throws Exception {
        CDXSearchService spy = spy(cdxSearchService);
        doThrow(new IOException("boom")).when(spy).openCdxConnection(anyString());

        SearchResults results = spy.getResults("http://example.com", null, null, 10, 0);

        assertThat(results.getEstimatedNumberResults()).isEqualTo(0);
        assertThat(results.getResults()).isNotNull().isEmpty();
    }

    @Test
    public void getCollectionForExactMatch_happyPath_returnsCollection() throws Exception {
        String cdxJson = "{\"url\":\"http://example.com\",\"timestamp\":\"20190101010101\","
                + "\"collection\":\"AWP1\"}\n";

        CDXSearchService spy = spy(cdxSearchService);
        doReturn(connectionReturning(cdxJson)).when(spy).openCdxConnection(anyString(), anyInt(), anyInt());

        String collection = spy.getCollectionForExactMatch("http://example.com", "20190101010101", 1000);

        assertThat(collection).isEqualTo("AWP1");
    }

    @Test
    public void getCollectionForExactMatch_noMatch_returnsNull() throws Exception {
        CDXSearchService spy = spy(cdxSearchService);
        doReturn(connectionReturning("")).when(spy).openCdxConnection(anyString(), anyInt(), anyInt());

        String collection = spy.getCollectionForExactMatch("http://example.com", "20190101010101", 1000);

        assertThat(collection).isNull();
    }

    @Test
    public void getCollectionForExactMatch_connectionThrows_returnsNull() throws Exception {
        CDXSearchService spy = spy(cdxSearchService);
        doThrow(new IOException("timed out")).when(spy).openCdxConnection(anyString(), anyInt(), anyInt());

        String collection = spy.getCollectionForExactMatch("http://example.com", "20190101010101", 1000);

        assertThat(collection).isNull();
    }
}
