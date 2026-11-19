package pt.arquivo.services.solr;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.junit4.SpringRunner;
import pt.arquivo.services.SearchQueryImpl;
import pt.arquivo.services.SearchResult;
import pt.arquivo.services.SearchResults;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the year balance ranking against a real index, which is the only place where it can be checked: the boost is
 * a function Solr has to parse, and it is only honoured by the request handler when the handler parses queries with
 * edismax. A prototype that silently does nothing looks exactly like one that works, so this is what tells them apart.
 *
 * <p>Needs the Solr of searchpages.textsearch.service.bean.solr.link to be reachable.
 */
@RunWith(SpringRunner.class)
public class YearBalanceTestIT {

    @Configuration
    @PropertySource("classpath:application.properties")
    static class ContextConfiguration {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        public SolrSearchService initSolrService() {
            return new SolrSearchService();
        }
    }

    @Autowired
    private SolrSearchService solrSearchService;

    private SearchResults query(double yearBalance) {
        SearchQueryImpl searchQuery = new SearchQueryImpl("eleições");
        searchQuery.setMaxItems(20);
        searchQuery.setYearBalance(yearBalance);
        return solrSearchService.query(searchQuery);
    }

    /** The year each result was archived on, averaged over the page of results. */
    private double meanYear(SearchResults searchResults) {
        return searchResults.getResults().stream()
                .map(SearchResult::getTstamp)
                .mapToInt(tstamp -> Integer.parseInt(tstamp.substring(0, 4)))
                .average()
                .orElse(0);
    }

    @Test
    public void balancedRankingBringsTheThinYearsForward() {
        SearchResults asItIs = query(0);
        SearchResults balanced = query(1.0);

        // The query has to work at all: a boost function Solr can't parse comes back as an error, and this service
        // replies with an empty result set when the Solr query fails
        assertThat(balanced.getResults()).isNotEmpty();

        // The same documents match either way, only their order changes
        assertThat(balanced.getEstimatedNumberResults()).isEqualTo(asItIs.getEstimatedNumberResults());

        // Normalized all the way, the first page should lean towards the years the archive holds the least of
        assertThat(meanYear(balanced)).isLessThan(meanYear(asItIs));
    }
}
