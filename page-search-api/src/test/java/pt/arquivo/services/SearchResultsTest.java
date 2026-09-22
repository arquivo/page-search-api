package pt.arquivo.services;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SearchResultsTest {

    @Test
    public void isLastPageResults() {
        SearchResults searchResults = new SearchResults();
        assertThat(searchResults.isLastPageResults()).isEqualTo(false);
    }

    @Test
    public void getResults_defaultsToEmptyListInsteadOfNull() {
        // Callers (e.g. PageSearchController#extractedText) call .getResults().size() without a null check,
        // relying on results never being null even when a code path never calls setResults()
        SearchResults searchResults = new SearchResults();
        assertThat(searchResults.getResults()).isNotNull().isEmpty();
    }
}