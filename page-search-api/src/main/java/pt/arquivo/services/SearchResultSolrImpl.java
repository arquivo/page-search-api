package pt.arquivo.services;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;

import java.io.IOException;

@JsonSerialize(using = SearchResultSerializer.class)
public class SearchResultSolrImpl implements SearchResult {

    private static final Log LOG = LogFactory.getLog(SearchResultSolrImpl.class);

    private String title;
    private String originalURL;
    private String linkToArchive;
    private String tstamp;
    private Long contentLength;
    private String digest;
    private String mimeType;
    private String encoding;
    private String date;
    private String linkToScreenshot;
    private String linkToNoFrame;
    private String linkToExtractedText;
    private String linkToMetadata;
    private String linkToOriginalFile;
    private String snippet;
    private String fileName;
    private String collection;
    private Long offset;
    private Integer statusCode;
    private String id;
    private String language;
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

    public SolrClient getSolrClient() {
        return solrClient;
    }

    public void setSolrClient(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

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

    public String[] getFields() {
        return fields;
    }

    public void setFields(String[] fields) {
        this.fields = fields;
    }

    public String getSearchResultId() {
        return getTstamp() + "/" + getOriginalURL();
    }
}


