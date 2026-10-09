package pt.arquivo.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.arquivo.services.SearchQuery;
import pt.arquivo.services.SearchQueryImpl;
import pt.arquivo.services.SearchResult;
import pt.arquivo.services.SearchResultSolrImpl;
import pt.arquivo.services.Timeline;

import java.util.ArrayList;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema
public class PageSearchResponse implements ApiResponse {

    private static Logger LOG = LoggerFactory.getLogger(PageSearchResponse.class);

    @Schema(description = "Name of the service, e.g. Arquivo.pt - the Portuguese web-archive.")
    private String serviceName;

    @Schema(description = "Base URL of the service, which next_page, previous_page and the links of each result start with.")
    private String linkToService;

    @Schema(description = "URL of the next page of results, with offset moved forward by maxItems. Left out on the last page.")
    @JsonProperty("next_page")
    private String nextPage;

    @Schema(description = "URL of the previous page of results, with offset moved back by maxItems. Left out on the first page.")
    @JsonProperty("previous_page")
    private String previousPage;

    @JsonIgnore
    private long totalItems;

    @Schema(description = "Approximate number of matching results across every page. When searching by q it is estimated "
            + "before deduplication, so it can be well above the number of results that can be paged through.")
    @JsonProperty("estimated_nr_results")
    private long estimatedNumberResults;

    // Typed as the SearchQuery interface, which doesn't carry the JSON names, so the schema comes from the implementation
    @Schema(implementation = SearchQueryImpl.class, description = "The parameters the search ran with, after defaults "
            + "and limits were applied, named as the query parameters. Only replied when searching by q.")
    @JsonProperty("request_parameters")
    private SearchQuery requestParameters;

    @Schema(description = "Spelling correction of q. Only replied when fields includes spellcheck, empty when the "
            + "query looks well spelled.")
    @JsonProperty("suggested_query")
    private String suggestedQuery;

    // Typed as the SearchResult interface, so the schema is taken from the class whose fields are serialized
    @ArraySchema(arraySchema = @Schema(description = "The results of this page, restricted to the requested fields."),
            schema = @Schema(implementation = SearchResultSolrImpl.class))
    @JsonProperty("response_items")
    private ArrayList<SearchResult> responseItems;

    @Schema(description = "Yearly breakdown of the matching documents. Only replied when searching by q with timeline=true.")
    @JsonProperty("timeline")
    private Timeline timeline;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getLinkToService() {
        return linkToService;
    }

    public void setLinkToService(String linkToService) {
        this.linkToService = linkToService;
    }

    public String getNextPage() {
        return nextPage;
    }

    public void setNextPage(String nextPage) {
        this.nextPage = nextPage;
    }

    public String getPreviousPage() {
        return previousPage;
    }

    public void setPreviousPage(String previousPage) {
        this.previousPage = previousPage;
    }

    public long getTotalItems() {
        return totalItems;
    }

    public void setTotalItems(long totalItems) {
        this.totalItems = totalItems;
    }

    public long getEstimatedNumberResults() {
        return estimatedNumberResults;
    }

    public void setEstimatedNumberResults(long estimatedNumberResults) {
        this.estimatedNumberResults = estimatedNumberResults;
    }

    public SearchQuery getRequestParameters() {
        return requestParameters;
    }

    public void setRequestParameters(SearchQuery requestParameters) {
        this.requestParameters = requestParameters;
    }

    public String getSuggestedQuery() {
        return suggestedQuery;
    }

    public void setSuggestedQuery(String suggestedQuery) {
        this.suggestedQuery = suggestedQuery;
    }

    public ArrayList<SearchResult> getResponseItems() {
        return responseItems;
    }

    public void setResponseItems(ArrayList<SearchResult> responseItems) {
        this.responseItems = responseItems;
    }

    public Timeline getTimeline() {
        return timeline;
    }

    public void setTimeline(Timeline timeline) {
        this.timeline = timeline;
    }

    public void setPagination(int maxItems, int offset, String queryString, boolean firstPage, boolean lastPage) {
        LOG.debug("setPagination parameters: maxItems %s, offset %s, queryString %s , firstPage %s, lastPage %s");

        int diffOffsetMaxItems = offset - maxItems;
        int previousOffset = (offset != 0 && diffOffsetMaxItems >= 0) ? (diffOffsetMaxItems) : 0;
        int nextOffset = offset + maxItems;

        if (!lastPage) {
            if (queryString.contains("offset=")) {
                String queryStringNextPage = queryString.replace("offset=" + offset, "offset=" + nextOffset);
                this.setNextPage(linkToService + "/textsearch?" + queryStringNextPage);
            } else {
                String queryStringNextPage = queryString.concat("&offset=" + nextOffset);
                this.setNextPage(linkToService + "/textsearch?" + queryStringNextPage); }
        }

        if (!firstPage) {
            if (queryString.contains("offset=")) {
                String queryStringPreviousPage = queryString.replace("offset=" + offset, "offset=" + previousOffset);
                this.setPreviousPage(linkToService + "/textsearch?" + queryStringPreviousPage);
            } else {
                this.setPreviousPage(linkToService + "/textsearch?" + queryString + "&offset=" + previousOffset);
            }
        }
    }
}
