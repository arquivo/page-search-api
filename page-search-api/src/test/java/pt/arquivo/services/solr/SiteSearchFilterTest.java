package pt.arquivo.services.solr;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SiteSearchFilterTest {

    @Test
    public void host_ignoresProtocolAndWww() {
        String expected = "surts:\\(pt,sapo, OR surts:\\(pt,sapo,\\)*";
        assertThat(SiteSearchFilter.parse("http://www.sapo.pt").toSolrClause()).isEqualTo(expected);
        assertThat(SiteSearchFilter.parse("https://www.sapo.pt").toSolrClause()).isEqualTo(expected);
        assertThat(SiteSearchFilter.parse("http://sapo.pt").toSolrClause()).isEqualTo(expected);
        assertThat(SiteSearchFilter.parse("sapo.pt").toSolrClause()).isEqualTo(expected);
        assertThat(SiteSearchFilter.parse("www.sapo.pt").toSolrClause()).isEqualTo(expected);
        assertThat(SiteSearchFilter.parse("https://www.sapo.pt/").toSolrClause()).isEqualTo(expected);
    }

    @Test
    public void host_matchesItsRootAndPagesOnly() {
        SiteSearchFilter filter = SiteSearchFilter.parse("www.sapo.pt");
        assertThat(filter.matches("(pt,sapo,")).isTrue();
        assertThat(filter.matches("(pt,sapo,)/noticias/")).isTrue();
        assertThat(filter.matches("(pt,sapo,blogs,delitodeopiniao,")).isFalse();
        assertThat(filter.matches("(pt,sapo,blogs,delitodeopiniao,)/post.html")).isFalse();
        assertThat(filter.matches("(pt,sapo,videos,)/")).isFalse();
        assertThat(filter.matches("(pt,sapotek,)/")).isFalse();
    }

    @Test
    public void wildcard_matchesHostAndAllSubdomains() {
        SiteSearchFilter filter = SiteSearchFilter.parse("*.sapo.pt");
        assertThat(filter.toSolrClause()).isEqualTo("surts:\\(pt,sapo,*");
        assertThat(filter.matches("(pt,sapo,")).isTrue();
        assertThat(filter.matches("(pt,sapo,)/noticias/")).isTrue();
        assertThat(filter.matches("(pt,sapo,blogs,delitodeopiniao,)/post.html")).isTrue();
        assertThat(filter.matches("(pt,sapotek,)/")).isFalse();
    }

    @Test
    public void wildcard_ignoresProtocolAndWww() {
        assertThat(SiteSearchFilter.parse("*.www.sapo.pt").toSolrClause()).isEqualTo("surts:\\(pt,sapo,*");
        assertThat(SiteSearchFilter.parse(" *.sapo.pt ").toSolrClause()).isEqualTo("surts:\\(pt,sapo,*");
    }

    @Test
    public void urlWithPath_matchesUrlsStartingWithIt() {
        SiteSearchFilter filter = SiteSearchFilter.parse("https://www.sapo.pt/some/path");
        assertThat(filter.toSolrClause()).isEqualTo("surts:\\(pt,sapo,\\)\\/some\\/path*");
        assertThat(filter.matches("(pt,sapo,)/some/path")).isTrue();
        assertThat(filter.matches("(pt,sapo,)/some/path/page.html")).isTrue();
        assertThat(filter.matches("(pt,sapo,)/other")).isFalse();
        assertThat(filter.matches("(pt,sapo,blogs,)/some/path")).isFalse();
    }

    @Test
    public void wildcardInTheMiddle_isNotAWildcard() {
        assertThat(SiteSearchFilter.parse("sapo.*.pt").toSolrClause()).doesNotContain(",*");
    }

    @Test
    public void blankValues_restrictNothing() {
        assertThat(SiteSearchFilter.parse("")).isNull();
        assertThat(SiteSearchFilter.parse(" ")).isNull();
        assertThat(SiteSearchFilter.parse("*.")).isNull();
        assertThat(SiteSearchFilter.parseAll(new String[] { "", "sapo.pt", " " })).hasSize(1);
    }

    @Test
    public void toFilterQuery_joinsSitesWithOr() {
        List<SiteSearchFilter> filters = SiteSearchFilter.parseAll(new String[] { "publico.pt", "*.iol.pt" });
        assertThat(SiteSearchFilter.toFilterQuery(filters)).isEqualTo(
                "surts:\\(pt,publico, OR surts:\\(pt,publico,\\)* OR surts:\\(pt,iol,*");
    }

    @Test
    public void anyMatches_matchesAgainstEverySite() {
        List<SiteSearchFilter> filters = Arrays.asList(SiteSearchFilter.parse("publico.pt"),
                SiteSearchFilter.parse("iol.pt"));
        assertThat(SiteSearchFilter.anyMatches(filters, "(pt,publico,)/")).isTrue();
        assertThat(SiteSearchFilter.anyMatches(filters, "(pt,iol,")).isTrue();
        assertThat(SiteSearchFilter.anyMatches(filters, "(pt,publico,inimigo,)/")).isFalse();
        assertThat(SiteSearchFilter.anyMatches(filters, "(pt,iol,tvi24,)/")).isFalse();
    }
}
