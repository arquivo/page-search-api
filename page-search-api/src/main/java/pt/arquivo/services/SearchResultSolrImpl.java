package pt.arquivo.services;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;

import java.io.IOException;

// The @Schema annotations document the result items of the API. SearchResultSerializer writes the fields below as
// they are, rather than through the getters, so the getters that aren't part of the reply are hidden from the docs.
// The versionHistory results, SearchResultNutchImpl, reply a subset of these same fields.
@Schema(name = "SearchResult", description = "An archived page or document. When searching by q, a result groups "
        + "the captures with the same content and its URL, timestamp and links are those of the oldest capture that "
        + "matches the query filters.")
@JsonSerialize(using = SearchResultSerializer.class)
public class SearchResultSolrImpl implements SearchResult {

    private static final Log LOG = LogFactory.getLog(SearchResultSolrImpl.class);

    @Schema(description = "Title of the page, cut to titleMaxLength. When listing captures with versionHistory it is the URL instead.")
    private String title;

    @Schema(description = "URL the capture was archived from.")
    private String originalURL;

    @Schema(description = "Link to replay the capture in Arquivo.pt.")
    private String linkToArchive;

    @Schema(description = "When the capture was made, in UTC, as yyyyMMddHHmmss, e.g. 20010610000000. Not when the page was published.")
    private String tstamp;

    @Schema(description = "Size of the capture in bytes, as stored in the archive.")
    private Long contentLength;

    @Schema(description = "Digest of the content. When searching by q it is an MD5 of the page's text and title, the same as "
            + "id. In versionHistory and metadata it is the digest of the archived payload, from the CDX index.")
    private String digest;

    @Schema(description = "MIME type of the capture, e.g. text/html or application/pdf.")
    private String mimeType;

    @Schema(description = "Never replied, kept as an accepted value of fields for compatibility.", deprecated = true)
    private String encoding;

    @Schema(description = "Never replied, kept as an accepted value of fields for compatibility. Use tstamp.", deprecated = true)
    private String date;

    @Schema(description = "Link to a screenshot of the capture.")
    private String linkToScreenshot;

    @Schema(description = "Link to replay the capture without the Arquivo.pt frame around it.")
    private String linkToNoFrame;

    @Schema(description = "Link to the text extracted from the capture, see /textextracted.")
    private String linkToExtractedText;

    @Schema(description = "Link to the metadata of the capture, see the metadata parameter.")
    private String linkToMetadata;

    @Schema(description = "Link to the capture as it was archived, without any replay rewriting.")
    private String linkToOriginalFile;

    @Schema(description = "Excerpt of the page's text around the query terms, which are wrapped in <em> tags. Cut to "
            + "snippetMaxLength. Only replied when searching by q.")
    private String snippet;

    @Schema(description = "Name of the WARC/ARC file holding the capture. Only replied by metadata.")
    private String fileName;

    @Schema(description = "Collection the capture belongs to, e.g. FAWP34.")
    private String collection;

    @Schema(description = "Byte offset of the capture within fileName. Only replied by metadata.")
    private Long offset;

    @Schema(description = "HTTP status code the capture was archived with. Only replied by versionHistory and metadata.")
    private Integer statusCode;

    @Schema(description = "Identifier of the result, an MD5 of the page's text and title. Only replied when searching by q, "
            + "and by default only when the service is configured to show it.")
    private String id;

    @Schema(description = "Language detected in the page's text, e.g. pt. Left out when none was detected. Only replied "
            + "when searching by q with language listed in fields.")
    private String language;

    // The value indexed, not the minLanguageConfidence tier: the LOW tier is indexed, and so replied, as NONE
    @Schema(description = "How confident the language detection is. NONE is the least confident, the tier that "
            + "minLanguageConfidence=LOW adds. Only replied when searching by q with languageConfidence listed in fields.",
            allowableValues = {"HIGH", "MEDIUM", "NONE"})
    private String languageConfidence;

    private String[] fields;

    private SolrClient solrClient;

    // Max time (ms) Solr is allowed to spend processing a query (timeAllowed param)
    private int timeAllowed = 60000;

    // The host/domain portion of the result's surt, used only internally to spread out same-host results across a
    // page (see SolrSearchService#diversifyByHost); never part of the API response.
    private String hostKey;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalURL() {
        return originalURL;
    }

    public void setOriginalURL(String originalURL) {
        this.originalURL = originalURL;
    }

    public String getLinkToArchive() {
        return linkToArchive;
    }

    public void setLinkToArchive(String linkToArchive) {
        this.linkToArchive = linkToArchive;
    }

    public String getTstamp() {
        return tstamp;
    }

    public void setTstamp(Long tstamp) {
        this.tstamp = String.valueOf(tstamp);
    }

    @Schema(hidden = true)
    public void setTimeStamp(String timeStamp) {
        this.tstamp = timeStamp;
    }

    public Long getContentLength() {
        return contentLength;
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getLinkToScreenshot() {
        return linkToScreenshot;
    }

    public void setLinkToScreenshot(String linkToScreenshot) {
        this.linkToScreenshot = linkToScreenshot;
    }

    public String getLinkToNoFrame() {
        return linkToNoFrame;
    }

    public void setLinkToNoFrame(String linkToNoFrame) {
        this.linkToNoFrame = linkToNoFrame;
    }

    public String getLinkToExtractedText() {
        return linkToExtractedText;
    }

    public void setLinkToExtractedText(String linkToExtractedText) {
        this.linkToExtractedText = linkToExtractedText;
    }

    public String getLinkToMetadata() {
        return linkToMetadata;
    }

    public void setLinkToMetadata(String linkToMetadata) {
        this.linkToMetadata = linkToMetadata;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getLinkToOriginalFile() {
        return linkToOriginalFile;
    }

    public void setLinkToOriginalFile(String linkToOriginalFile) {
        this.linkToOriginalFile = linkToOriginalFile;
    }

    @JsonIgnore
    @Override
    public String getExtractedText() {
        StringBuilder extractedText = new StringBuilder();
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.set("shards.tolerant", "true");
        solrQuery.setQuery("id:".concat(this.id));
        solrQuery.set("fl", "content,title");
        solrQuery.set("hl","false");
        solrQuery.set("timeAllowed", timeAllowed);
        LOG.info("ExtractedText Solr Query: " + solrQuery);
        try {
            QueryResponse queryResponse = solrClient.query(solrQuery);
            SolrDocument doc = queryResponse.getResults().get(0);
            extractedText.append(doc.getFieldValue("title"));
            extractedText.append(", ");
            extractedText.append(doc.getFieldValue("content"));
        } catch (SolrServerException | IOException e) {
            LOG.error("Error while querying Solr: ", e);
        }
        return extractedText.toString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getLanguageConfidence() {
        return languageConfidence;
    }

    public void setLanguageConfidence(String languageConfidence) {
        this.languageConfidence = languageConfidence;
    }

    @Schema(hidden = true)
    public SolrClient getSolrClient() {
        return solrClient;
    }

    public void setSolrClient(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    @Schema(hidden = true)
    public int getTimeAllowed() {
        return timeAllowed;
    }

    public void setTimeAllowed(int timeAllowed) {
        this.timeAllowed = timeAllowed;
    }

    @JsonIgnore
    public String getHostKey() {
        return hostKey;
    }

    public void setHostKey(String hostKey) {
        this.hostKey = hostKey;
    }

    @Schema(hidden = true)
    public String[] getFields() {
        return fields;
    }

    public void setFields(String[] fields) {
        this.fields = fields;
    }

    @Schema(hidden = true)
    public String getSearchResultId() {
        return getTstamp() + "/" + getOriginalURL();
    }
}


