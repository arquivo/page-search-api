package pt.arquivo.api;

import org.apache.solr.common.SolrException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import pt.arquivo.services.SearchQuery;
import pt.arquivo.services.SearchResult;
import pt.arquivo.services.SearchResultNutchImpl;
import pt.arquivo.services.SearchResults;
import pt.arquivo.services.SearchService;
import pt.arquivo.services.Timeline;
import pt.arquivo.services.cdx.ItemCDX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static pt.arquivo.services.cdx.CDXSearchService.getSearchResultNutch;


@SpringBootTest(classes = PageSearchApplication.class)
@AutoConfigureMockMvc
@RunWith(SpringRunner.class)
public class PageSearchControllerTest {

    @MockBean
    private SearchService searchService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void testSearchBySiteDefaultDedup() throws Exception {
        SearchResultNutchImpl mockSearchResult1 = new SearchResultNutchImpl();
        SearchResults mockSearchResults = new SearchResults();

        ArrayList<SearchResult> searchResults = new ArrayList<>();
        searchResults.add(mockSearchResult1);

        mockSearchResults.setResults(searchResults);

        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        String URI = "/textsearch?q=sapo&prettyPrint=true&siteSearch=example.com";
        String URI2 = "/textsearch?q=sapo&prettyPrint=true";
        String URI3 = "/textsearch?q=sapo&prettyPrint=true&siteSearch=example.com&dedupField=site";

        // test searchSite default dedupField
        RequestBuilder requestBuilder = MockMvcRequestBuilders.get(URI);
        MvcResult result = mockMvc.perform(requestBuilder).andReturn();

        MockHttpServletResponse response = result.getResponse();
        JSONObject jsonResponse = new JSONObject(response.getContentAsString());
        assertThat(jsonResponse.getJSONObject("request_parameters").getString("dedupField")).isEqualTo("url");

        // test default dedupField when query is by site
        requestBuilder = MockMvcRequestBuilders.get(URI2);
        result = mockMvc.perform(requestBuilder).andReturn();

        response = result.getResponse();

        jsonResponse = new JSONObject(response.getContentAsString());
        assertThat(jsonResponse.getJSONObject("request_parameters").getString("dedupField")).isEqualTo("title");

        // test custom dedupField on a search by site query
        requestBuilder = MockMvcRequestBuilders.get(URI3);
        result = mockMvc.perform(requestBuilder).andReturn();

        response = result.getResponse();

        jsonResponse = new JSONObject(response.getContentAsString());
        assertThat(jsonResponse.getJSONObject("request_parameters").getString("dedupField")).isEqualTo("site");
    }

    @Test
    public void pageSearchUnexpectedException() throws Exception {
        Mockito.when(searchService.query(Mockito.any()))
                .thenThrow(new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                        "Connection refused to solr-internal.arquivo.pt:8983"));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=sapo")).andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(500);
        JSONObject jsonResponse = new JSONObject(response.getContentAsString());
        // the raw exception message must not leak into the client-facing response
        assertThat(jsonResponse.getString("message")).doesNotContain("solr-internal.arquivo.pt");
        assertThat(jsonResponse.getInt("httpStatus")).isEqualTo(500);
    }

    @Test
    public void pageSearchInvalidSolrRequestReturns400() throws Exception {
        // e.g. an unknown/undefined Solr field used as the dedup collapse field: Solr rejects the query outright,
        // so this is the caller's fault, not a backend failure, and must not leak as a raw 500
        Mockito.when(searchService.query(Mockito.any()))
                .thenThrow(new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                        "undefined field collection"));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=sapo")).andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        JSONObject jsonResponse = new JSONObject(response.getContentAsString());
        // the raw exception message must not leak into the client-facing response
        assertThat(jsonResponse.getString("message")).doesNotContain("undefined field");
        assertThat(jsonResponse.getInt("httpStatus")).isEqualTo(400);
    }

    @Test
    public void pageSearchOffset() throws Exception {
        SearchResultNutchImpl mockSearchResult1 = new SearchResultNutchImpl();
        SearchResultNutchImpl mockSearchResult2 = new SearchResultNutchImpl();
        SearchResultNutchImpl mockSearchResult3 = new SearchResultNutchImpl();

        mockSearchResult1.setTitle("test result 1");
        mockSearchResult2.setTitle("test result 2");
        mockSearchResult3.setTitle("test result 3");

        ArrayList<SearchResult> searchResults = new ArrayList<>();
        searchResults.add(mockSearchResult1);
        searchResults.add(mockSearchResult2);
        searchResults.add(mockSearchResult3);

        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setNumberResults(3);
        mockSearchResults.setEstimatedNumberResults(10);
        mockSearchResults.setResults(searchResults);

        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        String URI = "/textsearch?q=sapo&offset=2&maxItems=1";

        RequestBuilder requestBuilder = MockMvcRequestBuilders.get(URI);
        MvcResult result = mockMvc.perform(requestBuilder).andReturn();


        MockHttpServletResponse response = result.getResponse();
        JSONObject jsonResponse = new JSONObject(response.getContentAsString());
        assertThat(jsonResponse.getString("next_page")).isEqualTo("http://localhost:8081/textsearch?q=sapo&offset=3&maxItems=1");
    }

    @Test
    public void pageSearch() throws Exception {
        /* Mainly verify API specification */

        SearchResultNutchImpl mockSearchResult1 = new SearchResultNutchImpl();
        mockSearchResult1.setTitle("test result 1");

        // just to force result spec verification below
        mockSearchResult1.setContentLength(3819);
        mockSearchResult1.setCollection("TESTE");
        mockSearchResult1.setTimeStamp("20192010");
        mockSearchResult1.setLinkToMetadata("URL");
        mockSearchResult1.setLinkToOriginalFile("URL");
        mockSearchResult1.setLinkToExtractedText("URL");
        mockSearchResult1.setLinkToArchive("URL");
        mockSearchResult1.setLinkToNoFrame("URL");
        mockSearchResult1.setLinkToScreenshot("URL");
        mockSearchResult1.setMimeType("text/html");
        mockSearchResult1.setDigest("0eae260a4bf611de89cf2eb96db21ba9");
        mockSearchResult1.setOffset(0);
        mockSearchResult1.setFileName("teste.arc.gz");
        mockSearchResult1.setStatusCode(200);
        mockSearchResult1.setSnippet("SNIPPET DE TESTE");
        mockSearchResult1.setDate("some epocho number");
        mockSearchResult1.setEncoding("utf-8");

        SearchResultNutchImpl mockSearchResult2 = new SearchResultNutchImpl();
        mockSearchResult2.setTitle("test result 2");

        // just to force result spec verification below
        mockSearchResult2.setContentLength(3819);
        mockSearchResult2.setCollection("TESTE");
        mockSearchResult2.setTimeStamp("20192010");
        mockSearchResult2.setLinkToMetadata("URL");
        mockSearchResult2.setLinkToOriginalFile("URL");
        mockSearchResult2.setLinkToExtractedText("URL");
        mockSearchResult2.setLinkToArchive("URL");
        mockSearchResult2.setLinkToNoFrame("URL");
        mockSearchResult2.setLinkToScreenshot("URL");
        mockSearchResult2.setMimeType("text/html");
        mockSearchResult2.setDigest("0eae260a4bf611de89cf2eb96db21ba9");
        mockSearchResult2.setOffset(0);
        mockSearchResult2.setFileName("teste.arc.gz");
        mockSearchResult2.setStatusCode(200);
        mockSearchResult2.setSnippet("SNIPPET DE TESTE");
        mockSearchResult2.setDate("some epocho number");
        mockSearchResult2.setEncoding("utf-8");

        ArrayList<SearchResult> searchResults = new ArrayList<>();
        searchResults.add(mockSearchResult1);
        searchResults.add(mockSearchResult2);

        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setNumberResults(2);
        mockSearchResults.setEstimatedNumberResults(10);
        mockSearchResults.setResults(searchResults);

        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        String URI = "/textsearch?q=sapo&prettyPrint=true";

        RequestBuilder requestBuilder = MockMvcRequestBuilders.get(URI);
        MvcResult result = mockMvc.perform(requestBuilder).andReturn();

        MockHttpServletResponse response = result.getResponse();
        JSONObject jsonResponse = new JSONObject(response.getContentAsString());
        assertThat(jsonResponse.getString("next_page")).isEqualTo("http://localhost:8081/textsearch?q=sapo&prettyPrint=true&offset=50");
        assertThat(jsonResponse.getString("serviceName")).isNotBlank();
        assertThat(jsonResponse.getString("linkToService")).isNotBlank();
        assertThat(jsonResponse.getInt("estimated_nr_results")).isEqualTo(10);

        // verify request_parameters
        JSONObject jsonRequests = jsonResponse.getJSONObject("request_parameters");
        assertThat(jsonRequests.getString("q")).isEqualTo("sapo");
        assertThat(jsonRequests.getInt("maxItems")).isEqualTo(50);
        assertThat(jsonRequests.getInt("titleMaxLength")).isEqualTo(300);
        assertThat(jsonRequests.getInt("snippetMaxLength")).isEqualTo(300);
        assertThat(jsonRequests.getInt("offset")).isEqualTo(0);
        assertThat(jsonRequests.getInt("dedupValue")).isEqualTo(2);
        assertThat(jsonRequests.getString("dedupField")).isEqualTo("title");


        JSONArray jsonArray = jsonResponse.getJSONArray("response_items");
        assertThat(jsonArray.length()).isEqualTo(2);

        // verify api result spec
        assertThat(jsonArray.getJSONObject(0).getString("title")).isEqualTo("test result 1");
        assertThat(jsonArray.getJSONObject(0).getString("tstamp")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("contentLength")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("digest")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("mimeType")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("encoding")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("date")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("linkToScreenshot")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("linkToNoFrame")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("linkToOriginalFile")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("snippet")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("fileName")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("collection")).isNotBlank();
        assertThat(jsonArray.getJSONObject(0).getString("offset")).isNotBlank();

        // verify pretty print
        response.getContentAsString().contains("{\n");
    }

    @Test
    public void pageSearchSpellcheck() throws Exception {
        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setResults(new ArrayList<>());
        mockSearchResults.setSuggestedQuery("torres novas");

        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        // spellcheck field requested and the query is misspelled: reply with the suggestion
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=torrse%20novsa&fields=spellcheck&maxItems=0")).andReturn();
        JSONObject jsonResponse = new JSONObject(result.getResponse().getContentAsString());
        assertThat(jsonResponse.getString("suggested_query")).isEqualTo("torres novas");

        // spellcheck field requested and the query is well spelled: reply with an empty suggestion
        mockSearchResults.setSuggestedQuery(null);
        result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=torres%20novas&fields=spellcheck&maxItems=0")).andReturn();
        jsonResponse = new JSONObject(result.getResponse().getContentAsString());
        assertThat(jsonResponse.getString("suggested_query")).isEqualTo("");

        // spellcheck field requested along with regular result fields
        mockSearchResults.setSuggestedQuery("torres novas");
        result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=torrse%20novsa&fields=title,snippet,spellcheck")).andReturn();
        jsonResponse = new JSONObject(result.getResponse().getContentAsString());
        assertThat(jsonResponse.getString("suggested_query")).isEqualTo("torres novas");

        // spellcheck field not requested: no suggestion field at all
        result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=torrse%20novsa")).andReturn();
        jsonResponse = new JSONObject(result.getResponse().getContentAsString());
        assertThat(jsonResponse.has("suggested_query")).isFalse();

        result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=torrse%20novsa&fields=title,snippet")).andReturn();
        jsonResponse = new JSONObject(result.getResponse().getContentAsString());
        assertThat(jsonResponse.has("suggested_query")).isFalse();
    }

    @Test
    public void pageSearchTimeline() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("2005", 1200L);
        counts.put("2006", 1800L);
        Map<String, Long> totalsPerYear = new LinkedHashMap<>();
        totalsPerYear.put("2005", 100000L);
        totalsPerYear.put("2006", 100000L);

        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setResults(new ArrayList<>());
        mockSearchResults.setTimeline(Timeline.of(counts, totalsPerYear));

        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        // timeline requested: the yearly counts and their impact come along with the results
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=eleicoes&timeline=true&maxItems=0")).andReturn();
        JSONObject jsonResponse = new JSONObject(result.getResponse().getContentAsString());
        assertThat(jsonResponse.getJSONObject("request_parameters").getBoolean("timeline")).isTrue();

        JSONObject jsonTimeline = jsonResponse.getJSONObject("timeline");
        assertThat(jsonTimeline.getJSONObject("counts").getLong("2005")).isEqualTo(1200L);
        assertThat(jsonTimeline.getJSONObject("impact").getDouble("2005")).isEqualTo(0.012);

        // timeline not requested: the reply is the one it always was
        result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=eleicoes")).andReturn();
        jsonResponse = new JSONObject(result.getResponse().getContentAsString());
        assertThat(jsonResponse.has("timeline")).isFalse();
        assertThat(jsonResponse.getJSONObject("request_parameters").has("timeline")).isFalse();

        // the backend couldn't compute the timeline: the search still replies, without it
        mockSearchResults.setTimeline(null);
        result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=eleicoes&timeline=true")).andReturn();
        jsonResponse = new JSONObject(result.getResponse().getContentAsString());
        assertThat(jsonResponse.has("timeline")).isFalse();
        assertThat(jsonResponse.getJSONObject("request_parameters").getBoolean("timeline")).isTrue();
    }

    @Test
    public void pageSearchYearBalance() throws Exception {
        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setResults(new ArrayList<>());

        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        // asked for without a strength: the ranking is balanced by the default amount
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=eleicoes&yearBalance=true")).andReturn();
        JSONObject jsonRequests = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("request_parameters");
        assertThat(jsonRequests.getDouble("yearBalance")).isEqualTo(0.5);

        // asked for with a strength of its own
        result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=eleicoes&yearBalance=0.25")).andReturn();
        jsonRequests = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("request_parameters");
        assertThat(jsonRequests.getDouble("yearBalance")).isEqualTo(0.25);

        // not asked for, or turned down: the reply is the one it always was
        result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=eleicoes")).andReturn();
        jsonRequests = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("request_parameters");
        assertThat(jsonRequests.has("yearBalance")).isFalse();

        result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=eleicoes&yearBalance=false")).andReturn();
        jsonRequests = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("request_parameters");
        assertThat(jsonRequests.has("yearBalance")).isFalse();

        // out of range, or not a number at all
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=eleicoes&yearBalance=2"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=eleicoes&yearBalance=-1"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=eleicoes&yearBalance=lots"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    public void pageSearchLanguage() throws Exception {
        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setResults(new ArrayList<>());

        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=sapo&language=PT")).andReturn();
        JSONObject jsonRequests = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("request_parameters");
        assertThat(jsonRequests.getString("language")).isEqualTo("pt");
        assertThat(jsonRequests.getString("minLanguageConfidence")).isEqualTo("HIGH");

        result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=sapo&language=pt&minLanguageConfidence=medium")).andReturn();
        jsonRequests = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("request_parameters");
        assertThat(jsonRequests.getString("minLanguageConfidence")).isEqualTo("MEDIUM");

        // no language filtering, so no confidence filtering either
        result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=sapo")).andReturn();
        jsonRequests = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("request_parameters");
        assertThat(jsonRequests.has("language")).isFalse();
        assertThat(jsonRequests.has("minLanguageConfidence")).isFalse();

        result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=sapo&language=pt&minLanguageConfidence=low")).andReturn();
        jsonRequests = new JSONObject(result.getResponse().getContentAsString())
                .getJSONObject("request_parameters");
        assertThat(jsonRequests.getString("minLanguageConfidence")).isEqualTo("LOW");

        // NONE is how the least confident tier is indexed, the API asks for it as LOW
        result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=sapo&language=pt&minLanguageConfidence=NONE")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    public void pageSearchNutch() throws Exception {
        ItemCDX item = new ItemCDX("URL", "123456789", "", "", null, "",
                null, "0", "");
        getSearchResultNutch(item);
    }

    @Test
    public void pageSearchQueryNullReturns400() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    public void pageSearchQueryLooksLikeUrlReturns400() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=example.com")).andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        JSONObject jsonResponse = new JSONObject(response.getContentAsString());
        assertThat(jsonResponse.getString("message")).contains("cdxserverapi");
    }

    @Test
    public void extractedTextIdWithoutSlashReturns404() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textextracted?m=no-slash-id")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    public void extractedTextInvalidMetadataReturns404() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textextracted?m=http://example.com/20190101000000abc")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    public void extractedTextEmptyResultsReturns404() throws Exception {
        SearchResults emptyResults = new SearchResults();
        emptyResults.setResults(new ArrayList<>());
        Mockito.when(searchService.query(Mockito.any(), eq(true))).thenReturn(emptyResults);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textextracted?m=http://example.com/20190101000000")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    public void extractedTextReturnsExtractedText() throws Exception {
        SearchResult searchResult = mock(SearchResult.class);
        when(searchResult.getExtractedText()).thenReturn("Hello world");
        ArrayList<SearchResult> resultsList = new ArrayList<>();
        resultsList.add(searchResult);
        SearchResults searchResults = new SearchResults();
        searchResults.setResults(resultsList);
        Mockito.when(searchService.query(Mockito.any(), eq(true))).thenReturn(searchResults);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/textextracted?m=http://example.com/20190101000000")).andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("Hello world");
    }

    @Test
    public void pageSearchItemsPerSiteFromToAppliedToSearchQuery() throws Exception {
        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setResults(new ArrayList<>());
        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        mockMvc.perform(MockMvcRequestBuilders
                .get("/textsearch?q=sapo&itemsPerSite=3&from=20200101&to=20211231")).andReturn();

        ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
        Mockito.verify(searchService).query(captor.capture());
        assertThat(captor.getValue().getLimitPerSite()).isEqualTo(3);
        assertThat(captor.getValue().getFrom()).isEqualTo("20200101000000");
        assertThat(captor.getValue().getTo()).isEqualTo("20211231000000");
    }

    @Test
    public void pageSearchXForwardedForHeaderUsed() throws Exception {
        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setResults(new ArrayList<>());
        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=sapo")
                .header("X-FORWARDED-FOR", "203.0.113.5")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    public void pageSearchUserAgentHeaderBlankIsTreatedAsAbsent() throws Exception {
        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setResults(new ArrayList<>());
        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=sapo")
                .header("User-Agent", "   ")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    public void pageSearchUserAgentHeaderSet() throws Exception {
        SearchResults mockSearchResults = new SearchResults();
        mockSearchResults.setResults(new ArrayList<>());
        Mockito.when(searchService.query(Mockito.any())).thenReturn(mockSearchResults);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch?q=sapo")
                .header("User-Agent", "TestAgent/1.0")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }
}