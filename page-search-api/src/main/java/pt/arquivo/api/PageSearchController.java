package pt.arquivo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.solr.common.SolrException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import pt.arquivo.api.exceptions.ApiNotFoundResourceException;
import pt.arquivo.api.exceptions.ApiRequestException;
import pt.arquivo.services.*;
import pt.arquivo.services.cdx.CDXSearchService;
import pt.arquivo.services.solr.YearBalance;
import pt.arquivo.utils.Utils;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;


@Tag(name = "PageSearch")
@RestController
public class PageSearchController {

    private static final Logger LOG = LoggerFactory.getLogger(PageSearchController.class);

    @Value("${searchpages.api.servicename}")
    private String serviceName;

    @Value("${searchpages.service.link}")
    private String linkToService;

    @Value("${searchpages.api.title.maxlength:300}")
    private int defaultTitleMaxLength;

    @Value("${searchpages.api.snippet.maxlength:300}")
    private int defaultSnippetMaxLength;

    @Autowired
    private CDXSearchService cdxSearchService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private ObjectMapper jacksonObjectMapper;

    @Autowired
    MetadataController metadataController;

    @Hidden
    @CrossOrigin
    @GetMapping(value = {"/urlsearch/{url}"})
    public @ResponseBody
    ApiResponse searchCdxURL(@PathVariable String url,
                             @RequestParam(value = "from", required = false) String from,
                             @RequestParam(value = "to", required = false) String to,
                             @RequestParam(value = "maxItems", defaultValue = "50", required = false) int limit,
                             @RequestParam(value = "offset", defaultValue = "0", required = false) int offset,
                             HttpServletRequest request) {
        LOG.info(String.format("Request to urlsearch (versionHistory) for url=%s", url));
        SearchResults searchResults = cdxSearchService.getResults(url, from, to, limit, offset);

        PageSearchResponse pageSearchResponse = new PageSearchResponse();
        pageSearchResponse.setResponseItems(searchResults.getResults());
        pageSearchResponse.setEstimatedNumberResults(searchResults.getEstimatedNumberResults());
        pageSearchResponse.setTotalItems(searchResults.getNumberResults());

        pageSearchResponse.setServiceName(serviceName);
        pageSearchResponse.setLinkToService(linkToService);

        boolean firstPage = offset <= 0;
        boolean lastPage = searchResults.isLastPageResults();

        pageSearchResponse.setPagination(limit, offset, request.getQueryString(), firstPage, lastPage);

        return pageSearchResponse;
    }

    @Operation(summary = "Get the extracted text of an Archived Page")
    @CrossOrigin
    @GetMapping(value = "/textextracted")
    public String extractedText(@RequestParam(value = "m") String id) {
        LOG.info(String.format("Request to /textextracted for ID=%s", id));
        String extractedText;

        int idx = id.lastIndexOf("/");
        if (idx > 0) {
            String[] versionIdSplited = {id.substring(0, idx), id.substring(idx + 1)};
            if (Utils.metadataValidator(versionIdSplited)) {
                SearchResults searchResults = queryByUrl(versionIdSplited);
                ArrayList<SearchResult> searchResultsArray = searchResults.getResults();
                if (searchResultsArray.size() > 0) {
                    extractedText = searchResultsArray.get(0).getExtractedText();
                    return extractedText;
                }
            }
        }
        throw new ApiNotFoundResourceException("Resource ID doesn't exist: " + id);
    }

    protected SearchResults queryByUrl(String[] versionIdSplited) {
        LOG.debug("Querying By Url with versionIdSplited as ", versionIdSplited);
        String url = versionIdSplited[0];
        String timeStamp = versionIdSplited[1];

        SearchQuery searchQuery = new SearchQueryImpl(url);
        searchQuery.setFrom(timeStamp);
        searchQuery.setTo(timeStamp);
        searchQuery.setMaxItems(1);

        return searchService.query(searchQuery, true);
    }

    /**
     * How strongly the ranking should lift the documents of the years the archive holds the least of. It is asked for
     * as a strength between 0 and 1, and yearBalance=true asks for it without picking one.
     *
     * @param yearBalance the requested value, null when the parameter wasn't sent
     * @return the strength to rank with, 0 when the ranking should be left untouched
     */
    protected static double parseYearBalance(String yearBalance) {
        if (yearBalance == null || yearBalance.trim().isEmpty() || yearBalance.equalsIgnoreCase("false")) {
            return SearchQueryImpl.MIN_YEAR_BALANCE;
        }
        if (yearBalance.equalsIgnoreCase("true")) {
            return YearBalance.DEFAULT_STRENGTH;
        }

        double strength;
        try {
            strength = Double.parseDouble(yearBalance.trim());
        } catch (NumberFormatException e) {
            throw new ApiRequestException("Invalid yearBalance, it takes true, false, or a value between "
                    + SearchQueryImpl.MIN_YEAR_BALANCE + " and " + SearchQueryImpl.MAX_YEAR_BALANCE + ": "
                    + yearBalance);
        }
        if (strength < SearchQueryImpl.MIN_YEAR_BALANCE || strength > SearchQueryImpl.MAX_YEAR_BALANCE) {
            throw new ApiRequestException("Invalid yearBalance, it goes from " + SearchQueryImpl.MIN_YEAR_BALANCE
                    + " (the ranking as it is) to " + SearchQueryImpl.MAX_YEAR_BALANCE + ": " + yearBalance);
        }
        return strength;
    }

    @Operation(summary = "Search for Archived Pages that match the query parameters")
    @CrossOrigin
    @GetMapping(value = "/textsearch")
    public @ResponseBody
    ApiResponse pageSearch(@RequestParam(value = "q", required = false) String query,
                           @RequestParam(value = "versionHistory", required = false) String url,
                           @RequestParam(value = "metadata", required = false) String id,
                           @RequestParam(value = "offset", required = false, defaultValue = "0") int offset,
                           @RequestParam(value = "maxItems", required = false, defaultValue = "50") int maxItems,
                           @Parameter(description = "Maximum number of characters returned in the title field. Titles longer than this are cut short and suffixed with an ellipsis (…). 0 disables truncation.")
                           @RequestParam(value = "titleMaxLength", required = false) Integer titleMaxLength,
                           @Parameter(description = "Maximum number of characters returned in the snippet field. Snippets longer than this are cut short. 0 disables truncation.")
                           @RequestParam(value = "snippetMaxLength", required = false) Integer snippetMaxLength,
                           @RequestParam(value = "siteSearch", required = false) String[] siteSearch,
                           @Parameter(description = "Field results are deduplicated by, keeping only the newest per distinct value. title (the default) collapses on the exact title. "
                                   + "collection collapses on the collection. type collapses on the mimetype. url collapses on the exact URL, so two pages are only "
                                   + "deduplicated if they are the very same URL, not just the same site. site is meant to collapse per site/domain instead, but "
                                   + "currently behaves exactly like url (see https://github.com/arquivo/pwa-technologies/issues/1619). Any other or missing value "
                                   + "falls back to title.",
                                   schema = @Schema(allowableValues = {"title", "collection", "type", "url", "site"}))
                           @RequestParam(value = "dedupField", required = false, defaultValue = "title") String dedupField,
                           @RequestParam(value = "itemsPerSite", required = false) Integer itemsPerSite,
                           @RequestParam(value = "dedupValue", required = false, defaultValue = "2") int dedupValue,
                           @RequestParam(value = "from", required = false) String from,
                           @RequestParam(value = "to", required = false) String to,
                           @RequestParam(value = "type", required = false) String[] type,
                           @RequestParam(value = "collection", required = false) String[] collection,
                           @Parameter(description = "Restrict the response to only these fields per result. Omit to return majority of fields.",
                                   array = @ArraySchema(schema = @Schema(allowableValues = {"title", "originalURL", "linkToArchive", "tstamp",
                                           "contentLength", "digest", "mimeType", "encoding", "date", "linkToScreenshot",
                                           "linkToNoFrame", "linkToExtractedText", "linkToMetadata", "linkToOriginalFile",
                                           "snippet", "fileName", "collection", "offset", "statusCode", "id", "language",
                                           "languageConfidence"})))
                           @RequestParam(value = "fields", required = false) String[] fields,
                           @RequestParam(value = "prettyPrint", required = false) boolean prettyPrint,
                           @RequestParam(value = "titleSearch", required = false) String titleSearch,
                           @Parameter(description = "When true, also return a timeline with the distribution of results per year.")
                           @RequestParam(value = "timeline", required = false, defaultValue = "false") boolean timeline,
                           @Parameter(description = "Lift the ranking of documents from the years the archive holds the least of. Takes true (default strength), false (no effect, the default), or a strength between "
                                   + SearchQueryImpl.MIN_YEAR_BALANCE + " and " + SearchQueryImpl.MAX_YEAR_BALANCE + ".")
                           @RequestParam(value = "yearBalance", required = false) String yearBalance,
                           @Parameter(description = "Only return pages detected as being written in this language, e.g. pt")
                           @RequestParam(value = "language", required = false) String language,
                           @Parameter(description = "Lowest language detection confidence the results may have: HIGH (default), MEDIUM or LOW. The tiers are ordinal, so MEDIUM also includes the HIGH results and LOW includes every result. Only applies when filtering by language, unless explicitly requested.",
                                   schema = @Schema(allowableValues = {"HIGH", "MEDIUM", "LOW"}))
                           @RequestParam(value = "minLanguageConfidence", required = false) String minLanguageConfidence,
                           HttpServletRequest request
    ) {
        long startTime;
        long endTime;
        long duration;
        startTime = System.currentTimeMillis();
        
        // Regex that recognizes URLs. The same regex is used on the front end to redirect to /url/search
        final String urlRegEx = "^\\s*(((http|https):\\/\\/)?([a-zA-Z\\d][-\\w\\.]*)\\.([a-zA-Z\\.]{2,6})([-\\/\\w\\p{L}\\.~,;:%&=?!+$#*@\\(?\\)?]*)\\/?)\\s*$";
        
        if (url != null) {
            return searchCdxURL(url, from, to, maxItems, offset, request);
        } else if (id != null) {
            return metadataController.getMetadata(id);

        } else if (query == null) {
            LOG.error("Invalid API Request " + request.getQueryString());
            throw new ApiRequestException("Invalid API Request");
        } else if (query.matches(urlRegEx)) {
            LOG.info("Blocked request due to URL in the q parameter: " + query);
            throw new ApiRequestException(
                "Please use the following Arquivo.pt APIs to search for URLs:\r\n" + //
                "\tURL search: CDX server API: https://arquivo.pt/cdxserverapi\r\n" + //
                "\tMemento API: https://arquivo.pt/memento"
            );
        }

        SearchQuery searchQuery = new SearchQueryImpl(query);
        searchQuery.setOffset(offset);
        searchQuery.setMaxItems(maxItems);
        searchQuery.setTitleMaxLength(titleMaxLength != null ? titleMaxLength : defaultTitleMaxLength);
        searchQuery.setSnippetMaxLength(snippetMaxLength != null ? snippetMaxLength : defaultSnippetMaxLength);

        // TODO to decrepate parameter
        if (itemsPerSite != null) {
            searchQuery.setLimitPerSite(itemsPerSite);
        }
        if (from != null) {
            searchQuery.setFrom(from);
        }
        if (to != null) {
            searchQuery.setTo(to);
        }
        searchQuery.setType(type);
        searchQuery.setSite(siteSearch);
        searchQuery.setCollection(collection);
        searchQuery.setFields(fields);
        searchQuery.setPrettyPrint(prettyPrint);
        searchQuery.setTitleSearch(titleSearch);
        searchQuery.setTimeline(timeline);
        searchQuery.setYearBalance(parseYearBalance(yearBalance));
        searchQuery.setLanguage(language);
        try {
            searchQuery.setMinLanguageConfidence(minLanguageConfidence);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid API Request " + request.getQueryString());
            throw new ApiRequestException(e.getMessage());
        }

        searchQuery.setDedupValue(dedupValue);
        if (request.getParameter("dedupField") == null && searchQuery.isSearchBySite()) {
            searchQuery.setDedupField("url");
        } else {
            searchQuery.setDedupField(dedupField);
        }

        SearchResults searchResults;
        try {
            searchResults = searchService.query(searchQuery);
        } catch (SolrException e) {
            // Solr rejects the query outright (e.g. an unknown field) instead of a genuine backend failure: it's the
            // request that's invalid, not the service, so this is reported as a 400 rather than leaking as a 500
            if (e.code() == SolrException.ErrorCode.BAD_REQUEST.code) {
                LOG.error("Invalid API Request " + request.getQueryString(), e);
                throw new ApiRequestException("Invalid search request, check the query parameters");
            }
            throw e;
        }

        PageSearchResponse pageSearchResponse = new PageSearchResponse();
        // When spellcheck is requested we always reply with suggested_query, empty when the query looks well spelled
        if (searchQuery.isSpellcheck()) {
            String suggestedQuery = searchResults.getSuggestedQuery();
            pageSearchResponse.setSuggestedQuery(suggestedQuery == null ? "" : suggestedQuery);
        }

        // The timeline is only replied to the queries that asked for it, and only when the backend could compute it
        if (searchQuery.isTimeline()) {
            pageSearchResponse.setTimeline(searchResults.getTimeline());
        }

        pageSearchResponse.setServiceName(serviceName);
        pageSearchResponse.setLinkToService(linkToService);

        pageSearchResponse.setRequestParameters(searchQuery);
        pageSearchResponse.setResponseItems(searchResults.getResults());
        pageSearchResponse.setEstimatedNumberResults(searchResults.getEstimatedNumberResults());
        pageSearchResponse.setTotalItems(searchResults.getNumberResults());

        boolean lastPage = searchResults.isLastPageResults();
        boolean firstPage = offset <= 0;

        String queryString = request.getQueryString();
        pageSearchResponse.setPagination(maxItems, offset, queryString, firstPage, lastPage);

        if (searchQuery.getPrettyPrint()) {
            LOG.debug("prettyPrint request");
            jacksonObjectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        } else {
            jacksonObjectMapper.disable(SerializationFeature.INDENT_OUTPUT);
        }
        StringBuffer requestUrl = request.getRequestURL();
        if (request.getQueryString() != null) {
            requestUrl.append("?");
            requestUrl.append(request.getQueryString());
        }

        String ipAddress = request.getHeader("X-FORWARDED-FOR");
        if (ipAddress == null) {
            ipAddress = request.getRemoteAddr();
        }

        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.trim().isEmpty())
            userAgent = "-";

        endTime = System.currentTimeMillis();
        duration = (endTime - startTime);


        LOG.info(SERPLogging.logResult(duration, ipAddress, userAgent, requestUrl.toString(), pageSearchResponse, searchQuery));
        return pageSearchResponse;
    }
}
