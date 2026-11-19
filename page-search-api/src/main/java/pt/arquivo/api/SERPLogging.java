package pt.arquivo.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.arquivo.services.SearchQuery;
import pt.arquivo.services.SearchResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;

public class SERPLogging {

    private static final Logger LOG = LoggerFactory.getLogger(SERPLogging.class);

    // Jackson 2's defaults keep the search parameters in their declaration order, as the log has always had them
    private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    public static String logResult(long duration, String ipAddress, String userAgent, String urlRequest, PageSearchResponse pageSearchResponse, SearchQuery searchQuery) {
        StringBuilder serpLog = new StringBuilder();
        serpLog.append(ipAddress);
        serpLog.append("\t");
        serpLog.append(userAgent);
        serpLog.append("\t");
        serpLog.append(urlRequest);
        serpLog.append("\t");
        serpLog.append(duration);
        serpLog.append(" ms");
        serpLog.append("\tsearch_parameters: ");

        try {
            String jsonString = MAPPER.writeValueAsString(searchQuery);
            serpLog.append(jsonString);
            serpLog.append("\tsearch_results: ");
            ArrayList<String> results = new ArrayList<>();
            for (SearchResult searchResult : pageSearchResponse.getResponseItems()) {
                results.add(searchResult.getSearchResultId());
            }
            serpLog.append(MAPPER.writeValueAsString(results));
        } catch (JacksonException e) {
            LOG.error("Something went wrong serializing object");
        }
        return serpLog.toString();
    }
}
