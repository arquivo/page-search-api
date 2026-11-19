package pt.arquivo.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class URLNormalizersTest {

    @Test
    public void stripEndingSlashes() {
        assertThat(URLNormalizers.stripEndingSlashes("http://example.com/")).isEqualTo("http://example.com");
        assertThat(URLNormalizers.stripEndingSlashes("http://example.com")).isEqualTo("http://example.com");
        assertThat(URLNormalizers.stripEndingSlashes("")).isEqualTo("");
    }

    @Test
    public void stripProtocolAndWWWUrl() {
        assertThat(URLNormalizers.stripProtocolAndWWWUrl("http://www.example.com/path")).isEqualTo("example.com/path");
        assertThat(URLNormalizers.stripProtocolAndWWWUrl("https://example.com")).isEqualTo("example.com");
        assertThat(URLNormalizers.stripProtocolAndWWWUrl("www.example.com")).isEqualTo("example.com");
        // numbered www (www2, www3, ...) variants are also stripped, and a trailing slash is dropped first
        assertThat(URLNormalizers.stripProtocolAndWWWUrl("www2.example.com/")).isEqualTo("example.com");
    }

    @Test
    public void canocalizeUrl() {
        // no path gets a trailing slash added
        assertThat(URLNormalizers.canocalizeUrl("http://example.com")).isEqualTo("http://example.com/");
        // already-canonical URL with a path is left untouched
        assertThat(URLNormalizers.canocalizeUrl("http://example.com/path")).isEqualTo("http://example.com/path");
        // angle brackets are stripped, host is lowercased, path case is preserved, ending slash is dropped first
        assertThat(URLNormalizers.canocalizeUrl("<http://EXAMPLE.com/Path/>")).isEqualTo("http://example.com/Path");
    }

    @Test
    public void canocalizeSurtUrl() {
        assertThat(URLNormalizers.canocalizeSurtUrl("http://www.example.com/path")).isEqualTo("(com,example,)/path");
        // a domain-only SURT has no closing parenthesis
        assertThat(URLNormalizers.canocalizeSurtUrl("http://www.example.com")).isEqualTo("(com,example,");
        assertThat(URLNormalizers.canocalizeSurtUrl("http://sub.example.com/a/b")).isEqualTo("(com,example,sub,)/a/b");
    }

    @Test
    public void surtToUrl_domainOnlyNoParens() {
        assertThat(URLNormalizers.surtToUrl("com,example,")).isEqualTo("https://example.com/");
    }

    @Test
    public void surtToUrl_domainOnlyWithOpenParen() {
        // matches the actual output of canocalizeSurtUrl() for a domain with no path
        assertThat(URLNormalizers.surtToUrl("(com,example,")).isEqualTo("https://example.com/");
    }

    @Test
    public void surtToUrl_domainAndPath() {
        assertThat(URLNormalizers.surtToUrl("(com,example,)/path")).isEqualTo("https://example.com/path");
    }

    @Test
    public void surtToUrl_subdomainAndPath() {
        assertThat(URLNormalizers.surtToUrl("(com,example,sub,)/a/b")).isEqualTo("https://sub.example.com/a/b");
    }

    @Test
    public void surtToUrl_singleTokenWithNoCommaSlashOrParens() {
        // a bare hostname-like string with no separators is treated as a single-component domain SURT
        assertThat(URLNormalizers.surtToUrl("not-a-surt-plain-url.com")).isEqualTo("https://not-a-surt-plain-url.com/");
    }

    @Test
    public void surtToUrl_invalidSurtFallsBackToTreatingInputAsUrl() {
        // has a '/' but no '(' or ')', so it doesn't match either SURT branch and is canonicalized as-is
        assertThat(URLNormalizers.surtToUrl("http://example.com/path")).isEqualTo("http://example.com/path");
    }

    @Test
    public void surtToUrl_roundTripsWithCanocalizeSurtUrl() {
        String surt = URLNormalizers.canocalizeSurtUrl("http://www.example.com/path");
        assertThat(URLNormalizers.surtToUrl(surt)).isEqualTo("https://example.com/path");
    }
}
