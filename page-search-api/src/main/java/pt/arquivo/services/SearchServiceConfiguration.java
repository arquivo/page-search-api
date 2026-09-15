package pt.arquivo.services;

public class SearchServiceConfiguration {
    private String startDate;
    private String serviceName;
    private String screenshotServiceEndpoint;
    private String waybackServiceEndpoint;
    private String waybackNoFrameServiceEndpoint;
    private String extractedTextServiceEndpoint;
    private String baseSolrUrl;
    private String textSearchServiceEndpoint;
    private boolean showIds;
    private int timeAllowedMs = 60000;

    public String getStartDate() {
        return startDate;
    }
    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }
    public String getServiceName() {
        return serviceName;
    }
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }
    public String getScreenshotServiceEndpoint() {
        return screenshotServiceEndpoint;
    }
    public void setScreenshotServiceEndpoint(String screenshotServiceEndpoint) {
        this.screenshotServiceEndpoint = screenshotServiceEndpoint;
    }
    public String getWaybackServiceEndpoint() {
        return waybackServiceEndpoint;
    }
    public void setWaybackServiceEndpoint(String waybackServiceEndpoint) {
        this.waybackServiceEndpoint = waybackServiceEndpoint;
    }
    public String getWaybackNoFrameServiceEndpoint() {
        return waybackNoFrameServiceEndpoint;
    }
    public void setWaybackNoFrameServiceEndpoint(String waybackNoFrameServiceEndpoint) {
        this.waybackNoFrameServiceEndpoint = waybackNoFrameServiceEndpoint;
    }
    public String getExtractedTextServiceEndpoint() {
        return extractedTextServiceEndpoint;
    }
    public void setExtractedTextServiceEndpoint(String extractedTextServiceEndpoint) {
        this.extractedTextServiceEndpoint = extractedTextServiceEndpoint;
    }
    public String getBaseSolrUrl() {
        return baseSolrUrl;
    }
    public void setBaseSolrUrl(String baseSolrUrl) {
        this.baseSolrUrl = baseSolrUrl;
    }
    public String getTextSearchServiceEndpoint() {
        return textSearchServiceEndpoint;
    }
    public void setTextSearchServiceEndpoint(String textSearchServiceEndpoint) {
        this.textSearchServiceEndpoint = textSearchServiceEndpoint;
    }
    public boolean isShowIds() {
        return showIds;
    }
    public void setShowIds(boolean showIds) {
        this.showIds = showIds;
    }
    public int getTimeAllowedMs() {
        return timeAllowedMs;
    }
    public void setTimeAllowedMs(int timeAllowedMs) {
        this.timeAllowedMs = timeAllowedMs;
    }
}
