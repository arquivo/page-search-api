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

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
public class SolrSearchServiceTestIT {

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

    @Test
    public void testMetadataRequest() {
        SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
        SearchResults searchResults = this.solrSearchService.query(searchQuery);

        assertThat(searchResults.getNumberResults()).isEqualTo(50);
        // assertThat(searchResults.isLastPageResults()).isTrue();

        SearchResult searchResult = searchResults.getResults().get(0);
    }


    // @Test
    // public void testSimpleQueryFirstResult() throws UnsupportedEncodingException {
    //     SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
    //     SearchResults searchResults = this.solrSearchService.query(searchQuery);

    //     // assertThat(searchResults.isLastPageResults()).isTrue();
    //     assertThat(searchResults.getEstimatedNumberResults()).isEqualTo(4171910L);
    //     assertThat(searchResults.getNumberResults()).isEqualTo(50);

    //     ArrayList<SearchResult> arraySearchResult = searchResults.getResults();
    //     SearchResult firstSearchResult = arraySearchResult.get(0);

    //     assertThat(firstSearchResult.getTitle()).isEqualTo("Portugal sapo. Sapo portugal on line and sapo portugal on line with portugal sapo");
    //     assertThat(firstSearchResult.getCollection()).isEqualTo("IA");
    //     // assertThat(firstSearchResult.getStatusCode()).isNull();
    //     assertThat(firstSearchResult.getTstamp()).isEqualTo("20070912072240");
    //     assertThat(firstSearchResult.getId()).isEqualTo("1DE965A3FF427D580131E4FBE0818787");
    //     // assertThat(firstSearchResult.getDigest()).isEqualTo("FC2C56AA56000FE6D881F2139F5DCA74");
    //     // TODO check other fields

    //     // TODO Teste this later when the result order is not always changing.
    //     // assertThat(firstSearchResult.getCollection()).isEqualTo("Roteiro");
    // }

    // @Test
    // public void testResultsBoundedQuery() {
    //     SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
    //     searchQuery.setOffset(5);
    //     searchQuery.setMaxItems(5);
    //     SearchResults searchResults = this.solrSearchService.query(searchQuery);

    //     assertThat(searchResults.isLastPageResults()).isFalse();
    //     assertThat(searchResults.getEstimatedNumberResults()).isEqualTo(4171910L);
    //     assertThat(searchResults.getNumberResults()).isEqualTo(5);
    // }


    // @Test
    // public void testTimeBoundedQuery() {
    //     SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
    //     searchQuery.setFrom("19961013150238");
    //     searchQuery.setTo("19961013150305");

    //     SearchResults searchResults = this.solrSearchService.query(searchQuery);
    //     assertThat(searchResults.getResults().size()).isEqualTo(3);
    // }

    // @Test
    // public void testCollectionBoundedQuery() {
    //     SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
    //     searchQuery.setCollection(new String[]{"collectionX"});

    //     SearchResults searchResults = this.solrSearchService.query(searchQuery);
    //     assertThat(searchResults.getResults().size()).isEqualTo(0);
    // }

    // @Test
    // public void testSiteBoundedQuery() {
    //     SearchQueryImpl searchQuery = new SearchQueryImpl("sapo");
    //     searchQuery.setSite(new String[]{"http://sapo.ua.pt/"});
    //     searchQuery.setDedupField("url");
    //     searchQuery.setDedupValue(1);

    //     SearchResults searchResults = this.solrSearchService.query(searchQuery);
    //     assertThat(searchResults.getResults().size()).isEqualTo(50);
    // }
}
