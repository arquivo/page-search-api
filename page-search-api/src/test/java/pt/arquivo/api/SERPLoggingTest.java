package pt.arquivo.api;

import org.junit.jupiter.api.Test;
import pt.arquivo.services.SearchQueryImpl;
import pt.arquivo.services.SearchResultNutchImpl;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

public class SERPLoggingTest {

    @Test
    public void logResult_buildsTabDelimitedLineWithQueryAndResultIds() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");

        SearchResultNutchImpl result = new SearchResultNutchImpl();
        result.setTimeStamp("20190101010101");
        result.setOriginalURL("http://example.com");

        ArrayList<pt.arquivo.services.SearchResult> responseItems = new ArrayList<>();
        responseItems.add(result);
        PageSearchResponse pageSearchResponse = new PageSearchResponse();
        pageSearchResponse.setResponseItems(responseItems);

        String log = SERPLogging.logResult(123L, "127.0.0.1", "curl/7.0", "/textsearch?q=sapo",
                pageSearchResponse, searchQuery);

        assertThat(log).startsWith("127.0.0.1\tcurl/7.0\t/textsearch?q=sapo\t123 ms\tsearch_parameters: ");
        assertThat(log).contains("\"q\":\"sapo\"");
        assertThat(log).contains("\tsearch_results: [\"20190101010101/http://example.com\"]");
    }
}
