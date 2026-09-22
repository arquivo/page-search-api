package pt.arquivo.services;

import java.util.ArrayList;

public class SearchResults {

    private long estimatedNumberResults;
    private long numberResults;
    private boolean lastPageResults = false;

    /** Spelling correction for the query, null when spellcheck wasn't requested or the query looks well spelled. */
    private String suggestedQuery;

    /** Yearly breakdown of the matching documents, null when the timeline wasn't requested. */
    private Timeline timeline;

    public Timeline getTimeline() {
        return timeline;
    }

    public void setTimeline(Timeline timeline) {
        this.timeline = timeline;
    }

    public String getSuggestedQuery() {
        return suggestedQuery;
    }

    public void setSuggestedQuery(String suggestedQuery) {
        this.suggestedQuery = suggestedQuery;
    }

    public boolean isLastPageResults() {
        return lastPageResults;
    }

    public void setLastPageResults(boolean lastPageResults) {
        this.lastPageResults = lastPageResults;
    }

    private ArrayList<SearchResult> results = new ArrayList<>();

    public ArrayList<SearchResult> getResults() {
        return results;
    }

    public void setResults(ArrayList<SearchResult> results) {
        this.results = results;
    }

    public long getEstimatedNumberResults() {
        return estimatedNumberResults;
    }

    public void setEstimatedNumberResults(long estimatedNumberResults) {
        this.estimatedNumberResults = estimatedNumberResults;
    }

    public long getNumberResults() {
        return numberResults;
    }

    public void setNumberResults(long numberResults) {
        this.numberResults = numberResults;
    }
}
