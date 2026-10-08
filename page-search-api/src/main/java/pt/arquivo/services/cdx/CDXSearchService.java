package pt.arquivo.services.cdx;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import pt.arquivo.services.*;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

public class CDXSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(CDXSearchService.class);

    private final String equalOP = "=";
    private final String andOP = "&";
    private final String outputCDX = "json";

    /**
     * Max lines {@link #getCollectionForExactMatch} asks CDX for. Has to cover every line indexed for the one
     * timestamp, usually one or two (one per source cdxj file that has the capture).
     */
    static final int EXACT_MATCH_CDX_LIMIT = 10;

    @Value("${searchpages.api.globaltimeout.ms}")
    private int timeoutreadConn;

    @Value("${wayback.service.cdx.timeout}")
    private int timeoutConn;

    @Value("${wayback.service.cdx.endpoint}")
    String waybackCdxEndpoint;

    @Value("${screenshot.service.endpoint}")
    String screenshotServiceEndpoint;

    @Value("${wayback.service.endpoint}")
    String waybackServiceEndpoint;

    @Value("${wayback.noframe.service.endpoint}")
    String waybackNoFrameServiceEndpoint;

    @Value("${searchpages.extractedtext.service.link}")
    String extractedTextServiceEndpoint;

    @Value("${searchpages.textsearch.service.link}")
    String textSearchServiceEndpoint;

    @Value("${searchpages.api.show.ids}")
    private boolean showIds;

    public SearchResults getResults(String url, String from, String to, int limitP, int start) {
        Gson gson = new Gson();
        SearchResults searchResultsResponse = new SearchResults();

        ArrayList<ItemCDX> cdxResults = new ArrayList<>();
        ArrayList<SearchResult> searchResults = new ArrayList<>();

        String urlCDX = generateCdxQuery(url, from, to);
        LOG.info("[getResults] CDX-API URL[" + urlCDX + "]");

        try {
            List<JsonObject> jsonValues = readJsonFromUrl(urlCDX);

            if (jsonValues == null) {
                LOG.error("Error while trying to get results from the CDX API.");
                searchResultsResponse.setNumberResults(0);
                searchResultsResponse.setEstimatedNumberResults(0);
                return searchResultsResponse;
            }

            // LOG.info("jsonValues Size: " + jsonValues.size());

            int limit = Math.min(jsonValues.size(), limitP + start);
            if (limit > 0) {
                for (int i = start; i < limit; i++) {
                    cdxResults.add(gson.fromJson(jsonValues.get(i), ItemCDX.class));
                }
            }

            // LOG.info("cdxResults Size: " + cdxResults.size());
            
            for (ItemCDX result : cdxResults) {

                SearchResultNutchImpl searchResult = getSearchResultNutch(result);
                searchResult.setTitle(result.getUrl());
                populateEndpointsLinks(searchResult, false);

                searchResults.add(searchResult);
                
            }
            searchResultsResponse.setResults(searchResults);
            searchResultsResponse.setEstimatedNumberResults(jsonValues.size());
            return searchResultsResponse;

        } catch (Exception e) {
            LOG.error("[getResults] URL[" + urlCDX + "] e ", e);
            searchResultsResponse.setEstimatedNumberResults(0);
            return searchResultsResponse;
        }
    }

    public static SearchResultNutchImpl getSearchResultNutch(ItemCDX result) {
        SearchResultNutchImpl searchResult = new SearchResultNutchImpl();
        searchResult.setFileName(result.getFilename());
        searchResult.setOffset(Long.parseLong(result.getOffset()));

        if (result.getLength() != null)
            searchResult.setContentLength(Long.parseLong(result.getLength()));

        searchResult.setDigest(result.getDigest());
        searchResult.setMimeType(result.getMime());
        searchResult.setTimeStamp(result.getTimestamp());
        searchResult.setOriginalURL(result.getUrl());

        if (result.getStatus() != null)
            searchResult.setStatusCode(Integer.parseInt(result.getStatus()));

        searchResult.setCollection(result.getCollection());

        return searchResult;
    }

    String generateCdxQuery(String url, String from, String to) {
        if (from == null) {
            from = "";
        }
        if (to == null) {
            to = "";
        }

        LOG.info("[CDXParser][getLink] url[" + url + "] from[" + from + "] to[" + to + "]");
        String urlEncoded = "";
        try {
            // FIX THIS encode or escape? xD
            urlEncoded = URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException un) {
            LOG.error("Error while encoding: ", un);
            urlEncoded = url;
        }
        LOG.info("[cdxparser] " + this.waybackCdxEndpoint);
        StringBuilder strCdxQuery = new StringBuilder();
        strCdxQuery.append(this.waybackCdxEndpoint)
                .append("?url")
                .append(equalOP)
                .append(urlEncoded)
                .append(andOP)
                .append("output")
                .append(equalOP)
                .append(outputCDX)
                .append(andOP)
                .append("from")
                .append(equalOP)
                .append(from)
                .append(andOP)
                .append("to")
                .append(equalOP)
                .append(to)
                .append(andOP)
                .append("reverse")
                .append(equalOP)
                .append("true");

        return strCdxQuery.toString();
    }


    /**
     * Connect and get response to the CDXServer
     *
     * @param strurl
     * @return
     */
    private ArrayList<JsonObject> readJsonFromUrl(String strurl) {
        InputStream is = null;
        ArrayList<JsonObject> jsonResponse = new ArrayList<JsonObject>();

        try {
            LOG.debug("[OPEN Connection]: " + strurl);
            URLConnection con = openCdxConnection(strurl);
            is = con.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            jsonResponse = readAll(rd);
            LOG.info("CDX Reply: "+jsonResponse);
            return jsonResponse;
        } catch (Exception e) {
            LOG.error("[readJsonFromUrl]" + e);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e1) {
                    LOG.error("[readJsonFromUrl] Close Stream: " + e1);
                }
            }
        }
    }

    /**
     * Opens a connection to the CDX server. Extracted as its own method so tests can stub it out via
     * {@code Mockito.spy(...)} instead of hitting the network.
     */
    URLConnection openCdxConnection(String strurl) throws IOException {
        return openCdxConnection(strurl, timeoutConn, timeoutreadConn);
    }

    /**
     * Same as {@link #openCdxConnection(String)}, but with explicit timeouts instead of the general purpose
     * ones, so callers with their own timeout budget (e.g. {@link #getCollectionForExactMatch}) don't have to
     * share it with the rest of the CDX traffic.
     */
    URLConnection openCdxConnection(String strurl, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        URL url = new URL(strurl);
        URLConnection con = strurl.startsWith("https")
                ? (HttpsURLConnection) url.openConnection()
                : url.openConnection();
        con.setConnectTimeout(connectTimeoutMs);
        con.setReadTimeout(readTimeoutMs);
        return con;
    }

    /**
     * Builds the CDX query for {@link #getCollectionForExactMatch}: captures of the url from the timestamp
     * onwards, capped at {@link #EXACT_MATCH_CDX_LIMIT} lines.
     *
     * It deliberately leaves out {@code to} (and {@code reverse}). pywb streams the matching lines and stops as
     * soon as it has {@code limit} of them, but it doesn't use {@code to} to stop scanning: it reads every
     * capture of the url and drops the ones past {@code to}. So {@code from=ts&to=ts} keeps reading until the
     * url's captures run out, unless {@code limit} lines match first, and there's no safe limit because the
     * number of lines for one timestamp isn't known upfront. For a heavily crawled url that's a long scan: on
     * production http://www.fccn.pt/ has 100,000+ captures across 50+ cdxj files and the lookup took 13-20s,
     * well past the lookup timeout, so the page was reported as not found (arquivo/pwa-technologies#1656).
     * Without {@code to}, the limit is always reached right after the wanted timestamp, so pywb stops after
     * reading a handful of lines (~0.3s for the same url), and the caller stops at the first later timestamp.
     */
    String generateExactMatchCdxQuery(String url, String timestamp) throws UnsupportedEncodingException {
        return this.waybackCdxEndpoint
                + "?url" + equalOP + URLEncoder.encode(url, StandardCharsets.UTF_8.name())
                + andOP + "output" + equalOP + outputCDX
                + andOP + "from" + equalOP + timestamp
                + andOP + "limit" + equalOP + EXACT_MATCH_CDX_LIMIT;
    }

    /**
     * Fetches just the collection code for an exact url+timestamp match, bounded by an explicit timeout
     * independent of the general purpose CDX timeouts. Used as a fast-path collection lookup so
     * {@code SolrSearchService#query(SearchQuery, boolean)} can build an exact-match query instead of an
     * expensive leading-wildcard one.
     *
     * @param timestamp the 14 digit capture timestamp (YYYYMMDDhhmmss), compared verbatim with CDX's
     * @return the collection code, or null if CDX has no match, times out, or fails for any reason
     */
    public String getCollectionForExactMatch(String url, String timestamp, int timeoutMs) {
        try {
            String urlCDX = generateExactMatchCdxQuery(url, timestamp);
            LOG.debug("[getCollectionForExactMatch] CDX-API URL[" + urlCDX + "]");
            try (InputStream is = openCdxConnection(urlCDX, timeoutMs, timeoutMs).getInputStream();
                    BufferedReader rd = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                // CDX can return several lines for the same url+timestamp (one per source cdxj file, e.g. an
                // aggregate "Others.cdxj" entry alongside the real per-collection one), and not every line
                // carries a "collection" field. Take the first line that does, rather than assuming it's on
                // line 1. Lines come sorted by timestamp, so the first later one means there's no match left.
                String line;
                while ((line = rd.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    JsonObject o = new JsonParser().parse(line.trim()).getAsJsonObject();
                    if (!o.has("timestamp") || !timestamp.equals(o.get("timestamp").getAsString())) {
                        return null;
                    }
                    if (o.has("collection")) {
                        return o.get("collection").getAsString();
                    }
                }
                return null;
            }
        } catch (Exception e) {
            LOG.warn("[getCollectionForExactMatch] CDX lookup failed for url[" + url + "] timestamp[" + timestamp + "]: " + e);
            return null;
        }
    }

    /**
     * build json struture with CDXServer response
     *
     * @param rd
     * @return
     * @throws IOException
     * @throws ParseException
     */
    private ArrayList<JsonObject> readAll(BufferedReader rd) throws IOException {
        ArrayList<JsonObject> json = new ArrayList<JsonObject>();
        String line;
        while ((line = rd.readLine()) != null) {
            LOG.debug("[JSON LINE] : " + line);
            JsonParser parser = new JsonParser();
            JsonObject o = parser.parse(line.trim()).getAsJsonObject();
            json.add(o);
        }
        return json;
    }


    void populateEndpointsLinks(SearchResultNutchImpl searchResult, boolean textMatch) throws UnsupportedEncodingException {

        searchResult.setLinkToArchive(waybackServiceEndpoint.concat("/")
                .concat(searchResult.getTstamp().concat("/").concat(searchResult.getOriginalURL())));

        searchResult.setLinkToNoFrame(waybackNoFrameServiceEndpoint.concat("/")
                .concat(searchResult.getTstamp()).concat("/").concat(searchResult.getOriginalURL()));

        searchResult.setLinkToScreenshot(screenshotServiceEndpoint.concat("?url=")
                .concat(URLEncoder.encode(searchResult.getLinkToNoFrame(), StandardCharsets.UTF_8.toString())));

        searchResult.setLinkToOriginalFile(waybackNoFrameServiceEndpoint.concat("/")
                .concat(searchResult.getTstamp()).concat("id_/").concat(searchResult.getOriginalURL()));

        searchResult.setLinkToMetadata(textSearchServiceEndpoint.concat("?metadata=")
                .concat(URLEncoder.encode(searchResult.getOriginalURL().concat("/")
                        .concat(searchResult.getTstamp()), StandardCharsets.UTF_8.toString())));

        if (textMatch) {
            searchResult.setLinkToExtractedText(extractedTextServiceEndpoint.concat("?m=")
                    .concat(URLEncoder.encode(searchResult.getOriginalURL().concat("/").concat(searchResult.getTstamp()), StandardCharsets.UTF_8.toString())));
        }
    }
}
