package pt.arquivo.services;

public interface SearchQuery {

    /** Value of the fields parameter that asks for the query to be spellchecked. Not a field of the results. */
    String SPELLCHECK_FIELD = "spellcheck";

    /** languageConfidence tiers a request can ask for, from the most to the least confident. */
    String LANGUAGE_CONFIDENCE_HIGH = "HIGH";
    String LANGUAGE_CONFIDENCE_MEDIUM = "MEDIUM";
    String LANGUAGE_CONFIDENCE_LOW = "LOW";

    String getQueryTerms();

    String getQuotedQueryTerms();

    void setQueryTerms(String queryTerms);

    int getOffset();

    void setOffset(int offset);

    int getMaxItems();

    void setMaxItems(int maxItems);

    int getTitleMaxLength();

    void setTitleMaxLength(int titleMaxLength);

    int getDedupValue();

    void setDedupValue(int dedupValue);

    String[] getSite();

    void setSite(String[] site);

    String[] getType();

    void setType(String[] type);

    String[] getCollection();

    void setCollection(String[] collection);

    String getFrom();

    void setFrom(String from);

    String getTo();

    void setTo(String to);

    String[] getFields();

    String getDedupField();

    void setDedupField(String dedupField);

    void setFields(String[] fields);

    boolean isSearchBySite();

    boolean isSearchByType();

    boolean isSearchByCollection();

    boolean isTimeBoundedQuery();

    Integer getLimitPerSite();

    void setLimitPerSite(Integer limitPerSite);

    boolean getPrettyPrint();

    void setPrettyPrint(boolean prettyPrint);

    String getTitleSearch();

    void setTitleSearch(String title);

    boolean isSearchByTitle();

    String getLanguage();

    void setLanguage(String language);

    boolean isSearchByLanguage();

    String getMinLanguageConfidence();

    void setMinLanguageConfidence(String minLanguageConfidence);

    boolean isSpellcheck();

    boolean isTimeline();

    void setTimeline(boolean timeline);

    double getYearBalance();

    void setYearBalance(double yearBalance);
}
