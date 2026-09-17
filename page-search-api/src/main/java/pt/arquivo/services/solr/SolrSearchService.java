package pt.arquivo.services.solr;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import pt.arquivo.services.SearchQuery;
import pt.arquivo.services.SearchResult;
import pt.arquivo.services.SearchResultSolrImpl;
import pt.arquivo.services.SearchResults;
import pt.arquivo.services.SearchService;
import pt.arquivo.services.SearchServiceConfiguration;
import pt.arquivo.services.Timeline;
import pt.arquivo.utils.URLNormalizers;
import pt.arquivo.utils.Utils;

public class SolrSearchService implements SearchService {

    private static final Logger LOG = LoggerFactory.getLogger(SolrSearchService.class);

    /** Result fields that are only returned when the user asks for them through the fields parameter. */
    private static final List<String> OPT_IN_FIELDS = Arrays.asList("language", "languageConfidence");

    /**
     * The minLanguageConfidence tiers, from the most to the least confident, and the value each one has in the
     * indexed languageConfidence field. The least confident tier is indexed as NONE.
     */
    private static final Map<String, String> LANGUAGE_CONFIDENCE_TIERS = new LinkedHashMap<String, String>();
    static {
        LANGUAGE_CONFIDENCE_TIERS.put(SearchQuery.LANGUAGE_CONFIDENCE_HIGH, "HIGH");
        LANGUAGE_CONFIDENCE_TIERS.put(SearchQuery.LANGUAGE_CONFIDENCE_MEDIUM, "MEDIUM");
        LANGUAGE_CONFIDENCE_TIERS.put(SearchQuery.LANGUAGE_CONFIDENCE_LOW, "NONE");
    }

    // TODO should upgrade this for the SolrCloudClient
    HttpSolrClient solrClient;

    @Value("${searchpages.api.startdate:19960101000000}")
    private String startDate;

    @Value("${searchpages.service.link:http://localhost:8081}")
    private String serviceName;

    @Value("${screenshot.service.endpoint:https://preprod.arquivo.pt/screenshot}")
    private String screenshotServiceEndpoint;

    @Value("${wayback.service.endpoint:https://preprod.arquivo.pt/wayback}")
    private String waybackServiceEndpoint;

    @Value("${wayback.noframe.service.endpoint:https://preprod.arquivo.pt/noFrame/replay}")
    private String waybackNoFrameServiceEndpoint;

    @Value("${searchpages.extractedtext.service.link:http://localhost:8081/textextracted}")
    private String extractedTextServiceEndpoint;

    @Value("${searchpages.textsearch.service.bean.solr.link:http://localhost:8983/solr/searchpages}")
    private String baseSolrUrl;

    @Value("${searchpages.textsearch.service.link:http://localhost:8081/textsearch}")
    private String textSearchServiceEndpoint;

    /** How long the number of documents the archive holds per year is reused for, it only moves when indexing does. */
    @Value("${searchpages.api.yearvolumes.ttl.ms:86400000}")
    private long yearVolumesTtlMillis = 86400000L;

    /** Max time (ms) Solr is allowed to spend processing a single query, so slow queries don't overwhelm it. */
    @Value("${searchpages.solr.timeallowed.ms:60000}")
    private int timeAllowed = 60000;

    private YearVolumes yearVolumes;

    private TimelineService timelineService;

    // For setting configs without autowired shanenigans
    public SolrSearchService(SearchServiceConfiguration configuration){
        this.startDate = configuration.getStartDate();
        this.serviceName = configuration.getServiceName();
        this.screenshotServiceEndpoint = configuration.getScreenshotServiceEndpoint();
        this.waybackServiceEndpoint = configuration.getWaybackServiceEndpoint();
        this.waybackNoFrameServiceEndpoint = configuration.getWaybackNoFrameServiceEndpoint();
        this.extractedTextServiceEndpoint = configuration.getExtractedTextServiceEndpoint();
        this.baseSolrUrl = configuration.getBaseSolrUrl();
        this.textSearchServiceEndpoint = configuration.getTextSearchServiceEndpoint();
        this.timeAllowed = configuration.getTimeAllowedMs();
    }

    public SolrSearchService(){}

    public HttpSolrClient getSolrClient() {
        if (this.solrClient == null) {
            LOG.info("Initing SolrClient pointing to " + this.baseSolrUrl);
            this.solrClient = new HttpSolrClient.Builder(this.baseSolrUrl).build();
        }
        return this.solrClient;
    }

    /**
     * The volumes of the archive per year, shared by the timeline and by the year balance ranking. Only the queries
     * asking for one of them pay for it, and only the first of those pays the Solr query it caches. Synchronized
     * because the requests racing on a cold start would otherwise get volumes each, and each one of those would
     * query Solr for a baseline of its own.
     */
    synchronized YearVolumes getYearVolumes() {
        if (this.yearVolumes == null) {
            this.yearVolumes = new YearVolumes(getSolrClient(), startYear(), yearVolumesTtlMillis);
        }
        return this.yearVolumes;
    }

    /**
     * Lets the tests work against an archive whose volumes are known, without a Solr to ask.
     */
    synchronized void setYearVolumes(YearVolumes yearVolumes) {
        this.yearVolumes = yearVolumes;
        this.timelineService = null;
    }

    /**
     * The timeline is only built for the queries that ask for it, so its service is only created when the first of
     * those queries arrives. Synchronized for the same reason as the volumes it reads.
     */
    synchronized TimelineService getTimelineService() {
        if (this.timelineService == null) {
            this.timelineService = new TimelineService(getYearVolumes());
        }
        return this.timelineService;
    }

    /**
     * The first year the archive has documents for, taken from the configured start date (e.g. 19960101000000).
     */
    private int startYear() {
        return Integer.parseInt(this.startDate.trim().substring(0, 4));
    }

    /**
     * Makes sure that the requested dedupField is a valid solr field. The API accepts a few convenience aliases that
     * get translated into the actual Solr field names: "site"/"surt"/"url" becomes "surtOldest", "mimetype" becomes
     * "type", and "collection" becomes "collectionOldest" ("collection" isn't a real Solr field, only
     * "collectionOldest" -single-valued- and "collections" -multi-valued, used for collection-restricted search-
     * are). "url" is also the implicit dedupField used internally for site-restricted searches that don't specify
     * one explicitly (see PageSearchController#pageSearch). The Solr field names themselves are also accepted,
     * case-insensitively, and returned with their proper casing (Solr field names are case-sensitive). When invalid
     * or empty, the dedup field defaults to "titleString" (the same as "title").
     * @param dedupField
     * @return
     */
    String sanitizeDedupField(String dedupField){
        if (dedupField == null) {
            dedupField = "";
        }
        dedupField = dedupField.toLowerCase();

        final List<String> validDedupFields = Arrays.asList(new String[] {"site","surt","url", "surtoldest", "mimetype","type","collection","collectionoldest","title","titlestring"});

        // By default dedup by title, if invalid dedupField then fallback to dedup by title
        if(!validDedupFields.contains(dedupField) || dedupField.equals("title") ){
            dedupField = "titleString";
        } else if (dedupField.equals("site") || dedupField.equals("surt") || dedupField.equals("url") || dedupField.equals("surtoldest")){
            dedupField = "surtOldest";
        } else if(dedupField.equals("mimetype")){
            dedupField = "type";
        } else if(dedupField.equals("collection") || dedupField.equals("collectionoldest")){
            dedupField = "collectionOldest";
        } else if(dedupField.equals("titlestring")){
            dedupField = "titleString";
        }

        return dedupField;
    }
    
    /**
     * The fields the user asked to have on each result. The spellcheck field asks for the query to be spellchecked,
     * it isn't a result field, so it doesn't count here.
     *
     * @param searchQuery
     * @return the requested result fields, or null when the default fields should be returned
     */
    private String[] resultFields(SearchQuery searchQuery) {
        if (searchQuery.getFields() == null) {
            return null;
        }
        List<String> fields = Arrays.stream(searchQuery.getFields())
                .filter(field -> !SearchQuery.SPELLCHECK_FIELD.equalsIgnoreCase(field))
                .collect(Collectors.toList());

        if (fields.isEmpty()) {
            return null;
        }
        return fields.toArray(new String[0]);
    }

    /**
     * Caps how long Solr is allowed to spend processing a query (timeAllowed param), so slow queries don't
     * overwhelm Solr. Package-private to allow direct unit testing.
     * @param solrQuery
     * @return the same solrQuery, for chaining
     */
    SolrQuery applyTimeAllowed(SolrQuery solrQuery) {
        solrQuery.set("timeAllowed", timeAllowed);
        return solrQuery;
    }

    /**
     * Converts the API request into an appropriate Solr query.
     * @param searchQuery
     * @return
     */
    SolrQuery convertSearchQuery(SearchQuery searchQuery) {
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.set("shards.tolerant", "true");
        applyTimeAllowed(solrQuery);

        if(searchQuery.getQueryTerms() == null){
            solrQuery.setQuery("*:*");
        } else {
            solrQuery.setQuery(sanitizeQuery(searchQuery.getQueryTerms(),solrQuery));
        }
        solrQuery.setStart(searchQuery.getOffset()); // No need to escape because offset and maxItems are integers
        solrQuery.setRows(searchQuery.getMaxItems());

        // Never surface blocked content
        solrQuery.addFilterQuery("-blocked:1");

        // Handle collection request:
        if (searchQuery.isSearchByCollection()) {
            boolean multipleCollection = false;
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("collections:");
            for (String collection : searchQuery.getCollection()) {
                if (multipleCollection)
                    stringBuilder.append(" OR ");
                stringBuilder.append(ClientUtils.escapeQueryChars(collection));
                multipleCollection = true;
            }
            solrQuery.addFilterQuery(stringBuilder.toString());
        }

        // Handle to/from request
        if (searchQuery.isTimeBoundedQuery()) {
            if (searchQuery.getFrom() != null) {
                solrQuery.addFilterQuery("dateLatest:[ "
                        + ClientUtils.escapeQueryChars(Utils.timestampToSolrDate(searchQuery.getFrom())) + " TO * ]");
            }
            if (searchQuery.getTo() != null) {
                solrQuery.addFilterQuery("dateOldest:[ * TO "
                        + ClientUtils.escapeQueryChars(Utils.timestampToSolrDate(searchQuery.getTo())) + "]");
            }
        }

        // Handle siteSearch request
        if (searchQuery.isSearchBySite()) {
            String[] sites = searchQuery.getSite();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("surts:");
            boolean multipleSite = false;
            for (String site : sites) {
                if (multipleSite)
                    stringBuilder.append(" OR surts:");

                String[] fullSurt = URLNormalizers.canocalizeSurtUrl(site).split("\\)");
                if (fullSurt.length == 1 || fullSurt[1].length() == 0) {
                    // If only a domain is given, search for all subdomains
                    stringBuilder.append(ClientUtils.escapeQueryChars(fullSurt[0]) + "*");
                } else {
                    // If a full URL is given, look only for the exact URL
                    stringBuilder.append(ClientUtils.escapeQueryChars(fullSurt[0] + ")" + fullSurt[1]) + "*");
                }
                multipleSite = true;
            }
            solrQuery.addFilterQuery(stringBuilder.toString());
        }

        // Handle titleSearch request
        if (searchQuery.isSearchByTitle()) {
            String title = searchQuery.getTitleSearch();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("titleString:");
            stringBuilder.append(ClientUtils.escapeQueryChars(title));
            solrQuery.addFilterQuery(stringBuilder.toString());
        }

        // Handle language request
        if (searchQuery.isSearchByLanguage()) {
            solrQuery.addFilterQuery("language:" + ClientUtils.escapeQueryChars(searchQuery.getLanguage()));
        }

        // Handle minLanguageConfidence request. The tiers are ordinal, so a request takes every tier down to the
        // one it asked for, e.g. MEDIUM also takes the HIGH documents
        String minLanguageConfidence = searchQuery.getMinLanguageConfidence();
        if (minLanguageConfidence != null) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("languageConfidence:(");
            boolean multipleTiers = false;
            for (Map.Entry<String, String> tier : LANGUAGE_CONFIDENCE_TIERS.entrySet()) {
                if (multipleTiers)
                    stringBuilder.append(" OR ");
                stringBuilder.append(tier.getValue());
                multipleTiers = true;

                if (tier.getKey().equals(minLanguageConfidence))
                    break;
            }
            stringBuilder.append(")");
            solrQuery.addFilterQuery(stringBuilder.toString());
        }

        // Handle deduplication:
        if (searchQuery.getDedupValue() >= 0){ //deduplication disabled if dedupValue == -1
            Integer dedupValue = searchQuery.getDedupValue();
            String dedupField = sanitizeDedupField(searchQuery.getDedupField());

            if(dedupValue > 0){ 
                dedupValue -= 1;
            }

            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("{!collapse field=")
                            .append(dedupField)
                            .append("}");
            solrQuery.addFilterQuery(stringBuilder.toString());
            if(dedupValue > 0){
                solrQuery.add("expand", "true");
                solrQuery.add("expand.rows", dedupValue.toString());
            }
        } 

        // Handle type request
        if (searchQuery.isSearchByType()) {
            String[] types = searchQuery.getType();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("type:");
            boolean multipleType = false;
            for (String type : types) {
                if (multipleType)
                    stringBuilder.append(" OR type:");

                type = type.toLowerCase();
                if (type.indexOf('/')<0){
                    switch (type) {
                        case "pdf":
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/pdf"));
                            break;
                        case "xls":
                        case "xlsx":
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/vnd.ms-excel"));
                            stringBuilder.append(" OR type:");
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
                            break;
                        case "ppt":
                        case "pptx":
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/vnd.ms-powerpoint"));
                            stringBuilder.append(" OR type:");
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
                            break;
                        case "doc":
                        case "docx":
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/msword"));
                            stringBuilder.append(" OR type:");
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
                            break;
                        case "ps":
                        case "ai":
                        case "eps":
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/postscript"));
                            break;
                        case "htm":
                        case "html":
                            stringBuilder.append(ClientUtils.escapeQueryChars("text/html"));
                            break;
                        case "rtf":
                            stringBuilder.append(ClientUtils.escapeQueryChars("application/rtf"));
                            break;
                        default:
                            stringBuilder.append("*" + ClientUtils.escapeQueryChars(type) + "*");
                            break;
                    }
                } else {
                    stringBuilder.append(ClientUtils.escapeQueryChars(type));
                }

                multipleType = true;
            }
            solrQuery.addFilterQuery(stringBuilder.toString());
        }


        // Optimization: Make sure we only ask the fields we need.
        // At most we'll only need these fields from Solr:
        String[] fieldsArray = new String[] { "id", "type", "urlTimestamp", "titleString", "language",
                "languageConfidence" };
        Hashtable<String, Boolean> fieldInclusivity = new Hashtable<String, Boolean>();
        Boolean needsSnippet = true; // Snippet is different, we'll handle it separately

        // If user only asks for certain fields, we don't need to ask for every field
        String[] requestedFields = resultFields(searchQuery);
        if (requestedFields != null) {
            for (int i = 0; i < fieldsArray.length; i++) {
                fieldInclusivity.put(fieldsArray[i], false);
            }

            // We always need urlTimestamp, it's where we get the collection, url and timestamp
            fieldInclusivity.put("urlTimestamp", true);


            // If we're deduping we'll need to get the dedup field to get the expand from solr 
            if (searchQuery.getDedupValue() > 1){
                String dedupField = sanitizeDedupField(searchQuery.getDedupField());
                fieldInclusivity.put(dedupField, true);
            }

            needsSnippet = false;
            for (String field : requestedFields) {
                switch (field) {
                    case "title":
                        fieldInclusivity.put("titleString", true);
                        break;
                    case "mimeType":
                        fieldInclusivity.put("type", true);
                        break;
                    case "snippet":
                        fieldInclusivity.put("id", true);
                        needsSnippet = true;
                        break;
                    case "language":
                        fieldInclusivity.put("language", true);
                        break;
                    case "languageConfidence":
                        fieldInclusivity.put("languageConfidence", true);
                        break;
                }
            }
        } else {
            for (int i = 0; i < fieldsArray.length; i++) {
                fieldInclusivity.put(fieldsArray[i], !OPT_IN_FIELDS.contains(fieldsArray[i]));
            }
        }
        StringBuilder stringBuilderFields = new StringBuilder();
        boolean multipleFields = false;
        for (int i = 0; i < fieldsArray.length; i++) {
            if (fieldInclusivity.get(fieldsArray[i]) == true) {
                if (multipleFields) {
                    stringBuilderFields.append(",");
                }
                stringBuilderFields.append(fieldsArray[i]);
                multipleFields = true;
            }
        }

        solrQuery.setFields(stringBuilderFields.toString());

        // Solr's default highlighter, fastVector, requires the index to carry full term vectors
        // (termVectors, termPositions, termOffsets), which ours doesn't, so it's forced explicitly on every
        // query rather than relying on server-side defaults (see arquivo/pwa-technologies#1609)
        solrQuery.set("hl.method", "unified");

        // if snippet / highlighting is needed, configure the highlighter parameters
        if (needsSnippet) {
            // Caps how many characters of the content field each snippet carries, so a single match doesn't return
            // the whole document (see arquivo/pwa-technologies#1635). 0 means "no cap", matching Solr's own meaning
            // for hl.fragsize as well as this API's convention for titleMaxLength.
            //
            // hl.fragsize alone is only a hint, not a hard cap: the Unified Highlighter's default SENTENCE boundary
            // scanner can treat a whole run of unpunctuated text (common in scraped web content) as a single
            // "sentence" and return it in full regardless of fragsize. Using a WORD boundary scanner instead keeps
            // fragments honoring fragsize even on such content. As a hard guarantee independent of these hints (and
            // of whatever hl.* defaults/invariants the Solr server itself may enforce), getHighlightedText also
            // re-clamps the assembled snippet to snippetMaxLength itself.
            solrQuery.set("hl.fragsize", searchQuery.getSnippetMaxLength());
            solrQuery.set("hl.fragsizeIsMinimum", "false");
            solrQuery.set("hl.bs.type", "WORD");
        }

        // If we don't need snippet we don't ask Solr for highligting (which is on by default since v5), and a query
        // asking for no results at all has nothing to highlight either
        if(!needsSnippet || searchQuery.getMaxItems() == 0){
            solrQuery.set("hl","false");
        }

        // Handle year balance: lift the documents of the years the archive holds the least of
        if (searchQuery.getYearBalance() > 0) {
            String boostFunction = YearBalance.boostFunction(getYearVolumes().perYear(), searchQuery.getYearBalance());
            if (boostFunction != null) {
                solrQuery.set("boost", boostFunction);
            }
        }

        // Every spellcheck parameter is sent explicitly, so the reply doesn't depend on the request handler defaults
        if (searchQuery.isSpellcheck()) {
            solrQuery.set("spellcheck.dictionary", "default");
            solrQuery.set("spellcheck", "true");
            solrQuery.set("spellcheck.extendedResults", "true");
            solrQuery.set("spellcheck.count", "1");
            solrQuery.set("spellcheck.alternativeTermCount", "5");
            solrQuery.set("spellcheck.collate", "true");
            solrQuery.set("spellcheck.collateExtendedResults", "true");
            solrQuery.set("spellcheck.maxCollationTries", "10");
            solrQuery.set("spellcheck.maxCollations", "1");
            // Quoted so collations are only kept when the corrected terms match as a phrase
            solrQuery.set("spellcheck.q", searchQuery.getQuotedQueryTerms());
        } else {
            solrQuery.set("spellcheck", "false");
        }

        return solrQuery;
    }

    /**
     * Converts the API request into the query that counts the matching documents per year. It carries the same filters
     * as the search itself, but not the deduplication: collapsing is a costly post filter (it multiplies the time of
     * the facet by an order of magnitude) and it would leave the counts of the query no longer comparable with the
     * counts of the whole archive that normalize them into the impact.
     *
     * @param searchQuery
     * @return
     */
    SolrQuery convertTimelineQuery(SearchQuery searchQuery) {
        SolrQuery solrQuery = convertSearchQuery(searchQuery);

        String[] filterQueries = solrQuery.getFilterQueries();
        if (filterQueries != null) {
            String[] withoutCollapse = Arrays.stream(filterQueries)
                    .filter(filterQuery -> !filterQuery.startsWith("{!collapse"))
                    .toArray(String[]::new);
            if (withoutCollapse.length == 0) {
                solrQuery.remove("fq");
            } else {
                solrQuery.setFilterQueries(withoutCollapse);
            }
        }
        solrQuery.remove("expand");
        solrQuery.remove("expand.rows");
        // Counting documents per year has no use for how they are scored
        solrQuery.remove("boost");

        // Only the counts are needed, no documents come back from this query
        solrQuery.setStart(0);
        solrQuery.setRows(0);
        solrQuery.setFields("id");
        solrQuery.set("hl", "false");
        solrQuery.set("spellcheck", "false");

        getTimelineService().addYearRangeFacet(solrQuery);
        return solrQuery;
    }

    /**
     * Runs the query that counts the matching documents per year and normalizes it against the size of the archive on
     * each year. A failure here doesn't fail the search, the reply just comes without the timeline.
     *
     * @param searchQuery
     * @return the timeline of the query, or null when Solr couldn't be asked for it
     */
    private Timeline queryTimeline(SearchQuery searchQuery) {
        SolrQuery timelineQuery = convertTimelineQuery(searchQuery);
        LOG.info("Solr Query (timeline): " + timelineQuery);
        try {
            return getTimelineService().buildTimeline(getSolrClient().query(timelineQuery));
        } catch (SolrServerException | IOException e) {
            LOG.error("Error querying Solr for the timeline: ", e);
            return null;
        }
    }

    /**
     * Escapes special symbols, but not all of them to allow for "" (exact match) and - (excluding) searches
     */
    String sanitizeQuery(String query, SolrQuery solrQuery){
        
        Pattern exactMatchPattern = Pattern.compile("(^|.*?\\s)\"([^\"]*)\"(\\s.*|$)");
        String line = query;
        Matcher m = exactMatchPattern.matcher(line);

        ArrayList <String> outsideQuotes = new ArrayList <String>();
        ArrayList <String> insideQuotes = new ArrayList <String>();
        while (m.find()) {
            outsideQuotes.add(m.group(1));
            insideQuotes.add(m.group(2));
            line = m.group(3);
            m = exactMatchPattern.matcher(line);
        }
        outsideQuotes.add(line);

        Pattern excludePattern = Pattern.compile("(^|.*?\\s)-([^\\s]+)(.*)");
        ArrayList <String> excludeTerms = new ArrayList<>();
        for (int i = 0; i < outsideQuotes.size(); i++){
            line = outsideQuotes.get(i);
            m = excludePattern.matcher(line);
            String sanitized = "";
            while(m.find()){
                sanitized += ClientUtils.escapeQueryChars(m.group(1));
                excludeTerms.add(ClientUtils.escapeQueryChars(m.group(2)));
                line = m.group(3);
                m = excludePattern.matcher(line);
            }
            sanitized += ClientUtils.escapeQueryChars(line);
            outsideQuotes.set(i, sanitized);
        }
        
        String sanitizedQuery = "";
        for(int i = 0; i<insideQuotes.size();i++){
            sanitizedQuery += outsideQuotes.get(i) + '"' + ClientUtils.escapeQueryChars(insideQuotes.get(i)) + '"';
        }
        sanitizedQuery += outsideQuotes.get(insideQuotes.size());

        for(int i = 0; i<excludeTerms.size(); i++){
            solrQuery.addFilterQuery("-content:"+excludeTerms.get(i));
            solrQuery.addFilterQuery("-title:"+excludeTerms.get(i));
        }

        return sanitizedQuery;
    }

    /**
     * Truncates the title to at most maxLength characters, appending an ellipsis when truncated.
     * A maxLength of 0 or less disables truncation.
     */
    private static String truncateTitle(String title, int maxLength) {
        if (maxLength <= 0 || title.length() <= maxLength) {
            return title;
        }
        return title.substring(0, maxLength).trim() + "…";
    }

    /**
     * Gets the highlighted text from the Solr "content" field to fill the API "snippet" field. If there was no text to be
     * hightlighted in the Solr "content" field, will use the first snippetMaxLength chars of the "content" field instead.
     *
     * @param queryResponse
     * @param fieldName
     * @param docId
     * @param snippetMaxLength maximum number of characters of the content field fallback, 0 or less disables truncation
     * @return
     */
    public String getHighlightedText(final QueryResponse queryResponse, final String fieldName, final String docId, final int snippetMaxLength) {
        String highlightedText = "";
        Map<String, Map<String, List<String>>> highlights = queryResponse.getHighlighting();

        Map<String, List<String>> fieldsSnippet;
        fieldsSnippet = highlights.getOrDefault(docId, null);

        if (fieldsSnippet != null) {
            List<String> snippets = fieldsSnippet.getOrDefault(fieldName, null);
            if (snippets != null) {
                highlightedText = getFragments(snippets, snippetMaxLength);
            }
        }

        // If we don't get highlighted text on the content we display the first snippetMaxLength chars of the content
        if (highlightedText.length() == 0) {
            SolrQuery solrQuery = new SolrQuery();
            solrQuery.set("shards.tolerant", "true");
            applyTimeAllowed(solrQuery);
            solrQuery.set("q", "id:" + docId);
            solrQuery.set("fl", "content");
            solrQuery.set("hl","false");
            solrQuery.set("spellcheck","false");
            try {
                SolrDocumentList solrDocumentList = getSolrClient().query(solrQuery).getResults();
                if (solrDocumentList.size() > 0) {
                    String content = (String) solrDocumentList.get(0).getFieldValue("content");
                    if (content != null && content.length() > 0) {
                        if (snippetMaxLength <= 0 || content.length() <= snippetMaxLength) {
                            highlightedText = content;
                        } else {
                            highlightedText = content.substring(0, snippetMaxLength) + "<span class=\"ellipsis\"> ... </span>";
                        }
                    }
                }
            } catch (SolrServerException | IOException e) {
                LOG.error("Error querying Solr: ", e);
            }
        }
        return highlightedText;
    }

    private static final String getFragments(List<String> snippets, int snippetMaxLength) {
        StringBuilder fragments = new StringBuilder();
        for (int i = 0; i < snippets.size(); i++) {
            fragments.append(truncateHighlightedFragment(snippets.get(i), snippetMaxLength));
            fragments.append("<span class=\"ellipsis\"> ... </span>");
        }
        return fragments.toString();
    }

    /**
     * Truncates a single highlighted fragment to at most maxLength characters, guaranteeing the cap regardless of
     * whatever the Solr highlighter itself actually honored (see arquivo/pwa-technologies#1635: hl.fragsize is only
     * a hint to it, not a hard limit). Cuts are kept from landing inside a "<em>"/"</em>" match-highlighting tag,
     * closing a still-open one so the markup stays valid. A maxLength of 0 or less disables truncation.
     */
    private static String truncateHighlightedFragment(String fragment, int maxLength) {
        if (maxLength <= 0 || fragment.length() <= maxLength) {
            return fragment;
        }
        String truncated = fragment.substring(0, maxLength);

        int lastOpenBracket = truncated.lastIndexOf('<');
        int lastCloseBracket = truncated.lastIndexOf('>');
        if (lastOpenBracket > lastCloseBracket) {
            truncated = truncated.substring(0, lastOpenBracket);
        }

        if (StringUtils.countMatches(truncated, "<em>") > StringUtils.countMatches(truncated, "</em>")) {
            truncated += "</em>";
        }

        return truncated;
    }

    /**
     * If obj1 is not null returns obj1. Otherwise returns obj2.
     *
     * @param obj1
     * @param obj2
     * @return Object
     */
    private Object coalesce(Object obj1, Object obj2) {
        if (obj1 == null) {
            return obj2;
        }
        return obj1;
    }

    /**
     * Extracts the collection from a string in the format collection/timestamp/surt (as returned by the Solr field urlTimestamp)
     *
     * @param tu
     * @return String
     */
    String timestampSurtToCollection(String tu) {
        return tu.substring(0, tu.indexOf("/"));
    }

    /**
     * Extracts the timestamp from a string in the format collection/timestamp/surt (as returned by the Solr field urlTimestamp)
     *
     * @param tu
     * @return String
     */
    String timestampSurtToTimestamp(String tu) {
        String r = tu.substring(tu.indexOf("/") + 1);
        return r.substring(0, r.indexOf("/"));
    }

    /**
     * Extracts the surt from a string in the format collection/timestamp/surt (as returned by the Solr field urlTimestamp)
     *
     * @param tu
     * @return String
     */
    String timestampSurtToSurt(String tu) {
        String r = tu.substring(tu.indexOf("/") + 1);
        return r.substring(r.indexOf("/") + 1);
    }

    /**
     * Given a list of strings in the format collection/timestamp/surt (as returned by the Solr field urlTimestamp),
     * will filter out entries that do not match the user query, i.e., timestamp outside to/from range, surt doesn't match
     * siteSearch or collection doesn't match collection search
     *
     */
    List<Object> filterUrlTimestamps(List <Object> urlstimestamps, Long to, Long from, String[] siteSearchSurts, String[] collectionSearch){
                urlstimestamps = urlstimestamps.stream()
                        .filter(u -> ((String) u).indexOf("/") >= 0)
                        .collect(Collectors.toList());

                // Filter out urltimestamps from wrong URLs if siteSearch was made
                if (siteSearchSurts != null) {
                    urlstimestamps = urlstimestamps.stream()
                            .filter(tu -> StringUtils.startsWithAny(timestampSurtToSurt((String) tu), siteSearchSurts))
                            .collect(Collectors.toList());
                }

                // Filter out urltimestamps from outside time-range if it's a time bounded query
                if (from != null) {
                    urlstimestamps = urlstimestamps.stream()
                            .filter(u -> Long.parseLong(timestampSurtToTimestamp((String) u)) >= from)
                            .collect(Collectors.toList());
                }
                if (to != null) {
                    urlstimestamps = urlstimestamps.stream()
                            .filter(u -> Long.parseLong(timestampSurtToTimestamp((String) u)) <= to)
                            .collect(Collectors.toList());
                }

                // Filter out collections if it's a collection bounded search
                if (collectionSearch != null) {
                    urlstimestamps = urlstimestamps.stream()
                            .filter(tu -> Arrays.asList(collectionSearch)
                                    .contains(timestampSurtToCollection((String) tu)))
                            .collect(Collectors.toList());
                }

                return urlstimestamps;
    }

    /**
     * Given a list of strings in the format collection/timestamp/surt (as returned by the Solr field urlTimestamp), will
     * return the one with the oldest timestamp
     */
    String getOldestUrlTimestamp(List <Object> urlstimestamps){
        String oldestTimestamp = null;
        String oldestUrlTimestamp = null;
        Iterator<Object> it = (Iterator<Object>) urlstimestamps.iterator();
        while (it.hasNext()) {
            String currentUrlTimestamp = (String) it.next();
            String currentTimestamp = timestampSurtToTimestamp(currentUrlTimestamp);

            if (oldestTimestamp == null || Long.parseLong(oldestTimestamp) > Long.parseLong(currentTimestamp)) {
                oldestTimestamp = currentTimestamp;
                oldestUrlTimestamp = currentUrlTimestamp;
            }
        }
        return oldestUrlTimestamp;
    }

    /**
     * Takes a SolrDocument object and converts it into a SearchResult, making sure the result matches the user query.  
     * Will return null if the document doesn't match the user query.
     * 
     * @param doc
     * @param queryResponse
     * @param to
     * @param from
     * @param siteSearchSurts
     * @param collectionSearch
     * @param replyFields
     * @return
     */
    private SearchResultSolrImpl getSearchResultfromSolrDocument(SolrDocument doc, QueryResponse queryResponse, Long to, Long from, String[] siteSearchSurts, String[] collectionSearch, String[] replyFields, int titleMaxLength, int snippetMaxLength ){
        String oldestUrl = null;
        String oldestTimestamp = null;
        String oldestCollection = null;

        
        List<Object> urlstimestamps = new ArrayList<Object>(doc.getFieldValues("urlTimestamp"));
        urlstimestamps = filterUrlTimestamps(urlstimestamps, to, from, siteSearchSurts, collectionSearch);

        // Sometimes after filtering we get no results
        if (urlstimestamps.size() == 0) {
            return null;
        }

        String oldestUrlTimestamp = getOldestUrlTimestamp(urlstimestamps);

        oldestTimestamp = timestampSurtToTimestamp(oldestUrlTimestamp);
        oldestUrl = URLNormalizers.surtToUrl(timestampSurtToSurt(oldestUrlTimestamp));
        oldestCollection = timestampSurtToCollection(oldestUrlTimestamp);
        

        SearchResultSolrImpl searchResult = new SearchResultSolrImpl();
        populateSearchResult(searchResult, queryResponse, doc, oldestUrl, oldestTimestamp, oldestCollection, replyFields, titleMaxLength, snippetMaxLength);
        searchResult.setSolrClient(this.solrClient);
        searchResult.setTimeAllowed(this.timeAllowed);
        return searchResult;
    }

    /**
     * Parses the reply from Solr and populates the API search results, making sure every result mateches the user query.
     * 
     * @param queryResponse
     * @param searchQuery
     * @return
     */
    private SearchResults parseQueryResponse(QueryResponse queryResponse, SearchQuery searchQuery) {
        SearchResults searchResults = new SearchResults();
        ArrayList<SearchResult> searchResultArrayList = new ArrayList<>();

        SolrDocumentList solrDocumentList = queryResponse.getResults();

        final Long to, from;
        final String[] siteSearchSurts;
        final String[] collectionSearch;
        final String[] replyFields;
        final Map<String, SolrDocumentList> expandedResults = queryResponse.getExpandedResults();

        int titleMaxLength = searchQuery.getTitleMaxLength();
        int snippetMaxLength = searchQuery.getSnippetMaxLength();

        // Check which fields the user asked for
        String[] requestedFields = resultFields(searchQuery);
        if (requestedFields == null) {
            // Default reply fields
            replyFields = new String[] { "title", "originalURL", "mimeType", "tstamp", "digest", "collection", "id",
                    "snippet", "linkToArchive", "linkToNoFrame", "linkToScreenshot", "linkToExtractedText",
                    "linkToMetadata", "linkToOriginalFile" };
        } else {
            replyFields = requestedFields;
        }

        // Check if the user searched for one or more specific websites
        if (searchQuery.isSearchBySite()) {
            siteSearchSurts = Arrays.asList(searchQuery.getSite()).stream()
                    .map(s -> URLNormalizers.canocalizeSurtUrl(s))
                    .collect(Collectors.toList())
                    .toArray(new String[0]);
        } else {
            siteSearchSurts = null;
        }

        // Check if the request is time-bounded 
        if (searchQuery.getFrom() != null) {
            from = Long.parseLong(Utils.canocalizeTimestamp(searchQuery.getFrom()));
        } else {
            from = null;
        }
        if (searchQuery.getTo() != null) {
            to = Long.parseLong(Utils.canocalizeTimestamp(searchQuery.getTo()));
        } else {
            to = null;
        }

        // Check if the user serched for one or more specific collections
        if (searchQuery.isSearchByCollection()) {
            collectionSearch = searchQuery.getCollection();
        } else {
            collectionSearch = null;
        }

        for (SolrDocument doc : solrDocumentList) {

            SearchResultSolrImpl searchResult = getSearchResultfromSolrDocument(doc,queryResponse,to,from,siteSearchSurts,collectionSearch,replyFields,titleMaxLength,snippetMaxLength);
            if(searchResult == null){
                continue;
            }
            searchResultArrayList.add(searchResult);
            
            if (expandedResults != null && expandedResults.size() > 0) {
                
                String dedupField = sanitizeDedupField(searchQuery.getDedupField());
                String expandedDedupValue = (String) doc.getFieldValue(dedupField);
                if (expandedDedupValue == null || !expandedResults.containsKey(expandedDedupValue)) {
                    continue;
                }

                Iterator<?> expandedDocumentIterator = expandedResults.get(expandedDedupValue).iterator();
                while(expandedDocumentIterator.hasNext()){
                    Object next = expandedDocumentIterator.next();
                    if (!(next instanceof SolrDocument)) {
                        continue;
                    }
                    SolrDocument expandedDoc = (SolrDocument) next;

                    SearchResultSolrImpl expandedResult = getSearchResultfromSolrDocument(expandedDoc,queryResponse,to,from,siteSearchSurts,collectionSearch,replyFields,titleMaxLength,snippetMaxLength);
                    if(expandedResult == null){
                        continue;
                    }
                    searchResultArrayList.add(expandedResult);
                }
            }

        }
        searchResults.setResults(searchResultArrayList);
        searchResults.setEstimatedNumberResults(queryResponse.getResults().getNumFound());
        searchResults.setNumberResults(queryResponse.getResults().size());

        if (searchQuery.isSpellcheck()) {
            searchResults.setSuggestedQuery(parseSuggestedQuery(queryResponse, searchQuery));
        }

        return searchResults;
    }

    /**
     * Extracts the spelling suggestion (collation) from a query response. Returns null when Solr had nothing to
     * suggest or when the suggestion is just the query the user already made.
     *
     * @param queryResponse
     * @param searchQuery
     * @return
     */
    String parseSuggestedQuery(QueryResponse queryResponse, SearchQuery searchQuery) {
        SpellCheckResponse spellCheckResponse = queryResponse.getSpellCheckResponse();
        if (spellCheckResponse == null) {
            return null;
        }
        String collation = spellCheckResponse.getCollatedResult();
        if (collation == null) {
            return null;
        }
        // The suggestion is quoted like the spellcheck.q we sent, so take back the quotes we added ourselves
        boolean quotedByUs = !searchQuery.getQueryTerms().equals(searchQuery.getQuotedQueryTerms());
        if (quotedByUs && collation.length() > 1 && collation.startsWith("\"") && collation.endsWith("\"")) {
            collation = collation.substring(1, collation.length() - 1);
        }
        if (collation.equalsIgnoreCase(searchQuery.getQueryTerms())) {
            return null;
        }
        return collation;
    }

    /**
     * Populates one API search result, making sure only to populate fields that the user asked for.
     * 
     * @param searchResult
     * @param queryResponse
     * @param doc
     * @param oldestUrl
     * @param oldestTimestamp
     * @param oldestCollection
     * @param replyFields
     */
    private void populateSearchResult(SearchResultSolrImpl searchResult, QueryResponse queryResponse, SolrDocument doc,
            String oldestUrl, String oldestTimestamp, String oldestCollection, String[] replyFields, int titleMaxLength, int snippetMaxLength) {
        for (String field : replyFields) {
            switch (field) {
                case "title":
                    String title = (String) coalesce(doc.getFieldValue("titleString"), "");
                    searchResult.setTitle(truncateTitle(title, titleMaxLength));
                    break;
                case "originalURL":
                    searchResult.setOriginalURL(oldestUrl);
                    break;
                case "mimeType":
                    searchResult.setMimeType((String) coalesce(doc.getFieldValue("type"), ""));
                    break;
                case "tstamp":
                    searchResult.setTstamp(Long.parseLong(oldestTimestamp));
                    break;
                case "digest":
                    searchResult.setDigest((String) coalesce(doc.getFieldValue("id"), ""));
                    break;
                case "collection":
                    searchResult.setCollection(oldestCollection);
                    break;
                case "id":
                    searchResult.setId((String) coalesce(doc.getFieldValue("id"), ""));
                    break;
                case "snippet":
                    searchResult.setSnippet(getHighlightedText(queryResponse, "content", (String) doc.get("id"), snippetMaxLength));
                    break;
                case "linkToArchive":
                    searchResult.setLinkToArchive(waybackServiceEndpoint + "/" + oldestTimestamp + "/" + oldestUrl);
                    break;
                case "linkToNoFrame":
                    searchResult.setLinkToNoFrame(
                            waybackNoFrameServiceEndpoint + "/" + oldestTimestamp + "/" + oldestUrl);
                    break;
                case "linkToScreenshot":
                    try {
                        searchResult.setLinkToScreenshot(screenshotServiceEndpoint +
                                "?url="
                                + URLEncoder.encode(
                                        waybackNoFrameServiceEndpoint + "/" + oldestTimestamp + "/" + oldestUrl,
                                        StandardCharsets.UTF_8.toString()));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                    break;
                case "linkToExtractedText":
                    try {
                        searchResult.setLinkToExtractedText(extractedTextServiceEndpoint.concat("?m=")
                                .concat(URLEncoder.encode(oldestUrl.concat("/").concat(oldestTimestamp),
                                        StandardCharsets.UTF_8.toString())));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                    break;
                case "linkToMetadata":
                    try {
                        searchResult.setLinkToMetadata(textSearchServiceEndpoint.concat("?metadata=")
                                .concat(URLEncoder.encode(oldestUrl.concat("/").concat(oldestTimestamp),
                                        StandardCharsets.UTF_8.toString())));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                    break;
                case "linkToOriginalFile":
                    searchResult.setLinkToOriginalFile(
                            waybackNoFrameServiceEndpoint + "/" + oldestTimestamp + "id_/" + oldestUrl);
                    break;
                // Left out of the reply when the document has no detected language, there is no empty value for it
                case "language":
                    searchResult.setLanguage((String) doc.getFieldValue("language"));
                    break;
                case "languageConfidence":
                    searchResult.setLanguageConfidence((String) doc.getFieldValue("languageConfidence"));
                    break;

            }

        }
    }

    /**
     * Implementation of the SearchService query by URL (used in queryByUrl). This is only used for metadata and
     * textextracted to get a specific entry, so it will output at most one entry. Deduplication and snippet is 
     * turned off for this query. Site search does not use this, it's handled by the other query function.
     */
    @Override
    public SearchResults query(SearchQuery searchQuery, boolean urlSearch) {
        if (urlSearch) {
            String queryTerms = searchQuery.getQueryTerms();
            String tstamp = searchQuery.getFrom();
            List<String> solrQueryForSites = Arrays.asList(queryTerms.split(",")).stream()
                    .filter(url -> Utils.urlValidator(url))
                    .map(url -> URLNormalizers.canocalizeSurtUrl(url))
                    .map(surt -> "urlTimestamp:" + "*/" + Utils.canocalizeTimestamp(tstamp) + "/" + ClientUtils.escapeQueryChars(surt))
                    .collect(Collectors.toList());
            SolrQuery solrQuery = new SolrQuery();
            solrQuery.set("shards.tolerant", "true");
            applyTimeAllowed(solrQuery);
            solrQuery.set("q", String.join(" OR ", solrQueryForSites));
            solrQuery.addFilterQuery("-blocked:1");
            solrQuery.set("fl","id,type,tstamp,urlTimestamp,surt,titleString,collection,url");
            solrQuery.set("hl","false");
            solrQuery.set("spellcheck","false");

            LOG.info("Solr Query (queryByUrl): "+solrQuery);
            searchQuery.setFields(new String[] { "title", "originalURL", "mimeType", "tstamp", "digest", "collection", "id",
            "linkToArchive", "linkToNoFrame", "linkToScreenshot", "linkToExtractedText",
            "linkToMetadata", "linkToOriginalFile" }); // Everything except the snippet
           
            try {
                QueryResponse queryResponse = this.getSolrClient().query(solrQuery);
                SearchResults searchResults = parseQueryResponse(queryResponse, searchQuery);
                return searchResults;
            } catch (SolrServerException | IOException e) {
                LOG.error("Error querying Solr: ", e);
                return null;
            }
        } else {    
            return query(searchQuery);
        }
    }

    /**
     * Implementation of the SearchService query. This is the function that is used for most queries.
     */
    @Override
    public SearchResults query(SearchQuery searchQuery) {
        SolrQuery solrQuery = convertSearchQuery(searchQuery);
        LOG.info("Solr Query: "+solrQuery);
        try {
            QueryResponse queryResponse = this.getSolrClient().query(solrQuery);
            SearchResults searchResults = parseQueryResponse(queryResponse, searchQuery);
            searchResults.setLastPageResults(isLastPage(searchResults.getEstimatedNumberResults(), searchQuery));
            if (searchQuery.isTimeline()) {
                searchResults.setTimeline(queryTimeline(searchQuery));
            }
            return searchResults;
        } catch (SolrServerException | IOException e) {
            LOG.error("Error querying Solr: ", e);
        }

        SearchResults searchResults = new SearchResults();
        searchResults.setEstimatedNumberResults(0);
        searchResults.setLastPageResults(false);
        searchResults.setNumberResults(0);
        return searchResults;
    }

    public static boolean isLastPage(long numberOfResults, SearchQuery searchQuery) {
        return numberOfResults <= searchQuery.getOffset() + searchQuery.getMaxItems();
    }

}
