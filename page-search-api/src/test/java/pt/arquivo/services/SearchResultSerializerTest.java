package pt.arquivo.services;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

public class SearchResultSerializerTest {

    private SearchResultSerializer serializer;
    private SearchResultSolrImpl result;
    private HttpSolrClient solrClient;

    @Before
    public void setUp() {
        serializer = new SearchResultSerializer();
        result = new SearchResultSolrImpl();
        result.setTitle("Example Title");
        result.setOriginalURL("http://example.com");
        result.setId("doc123");
        solrClient = new HttpSolrClient.Builder("http://localhost:1/solr").build();
        result.setSolrClient(solrClient);
    }

    @After
    public void tearDown() throws IOException {
        solrClient.close();
    }

    private JsonNode serialize() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        StringWriter writer = new StringWriter();
        // Use an ObjectMapper-backed generator (not a bare JsonFactory) so it can serialize
        // any synthetic/instrumentation fields the JVM may inject (e.g. coverage tooling),
        // since the class under test iterates over ALL declared fields via reflection.
        JsonGenerator generator = mapper.getFactory().createGenerator(writer);
        serializer.serialize(result, generator, null);
        generator.close();
        return mapper.readTree(writer.toString());
    }

    @Test
    public void defaultMode_neverLeaksInternalFields() throws IOException {
        serializer.showIds = false;
        JsonNode node = serialize();

        assertThat(node.has("solrClient")).isFalse();
        assertThat(node.has("LOG")).isFalse();
        assertThat(node.has("fields")).isFalse();
        assertThat(node.get("title").asText()).isEqualTo("Example Title");
        assertThat(node.get("originalURL").asText()).isEqualTo("http://example.com");
    }

    @Test
    public void defaultMode_hidesIdWhenShowIdsIsFalse() throws IOException {
        serializer.showIds = false;
        JsonNode node = serialize();
        assertThat(node.has("id")).isFalse();
    }

    @Test
    public void defaultMode_showsIdWhenShowIdsIsTrue() throws IOException {
        serializer.showIds = true;
        JsonNode node = serialize();
        assertThat(node.get("id").asText()).isEqualTo("doc123");
    }

    @Test
    public void defaultMode_omitsNullFields() throws IOException {
        serializer.showIds = false;
        result.setDigest(null);
        JsonNode node = serialize();
        assertThat(node.has("digest")).isFalse();
    }

    @Test
    public void defaultMode_neverLeaksTimeAllowed() throws IOException {
        serializer.showIds = false;
        result.setTimeAllowed(5000);
        JsonNode node = serialize();
        assertThat(node.has("timeAllowed")).isFalse();
    }

    @Test
    public void whitelistMode_neverLeaksTimeAllowedEvenIfRequested() throws IOException {
        serializer.showIds = false;
        result.setTimeAllowed(5000);
        result.setFields(new String[] { "title", "timeAllowed" });
        JsonNode node = serialize();
        assertThat(node.has("timeAllowed")).isFalse();
    }

    @Test
    public void whitelistMode_onlyIncludesRequestedFields() throws IOException {
        serializer.showIds = false;
        result.setFields(new String[] { "title" });
        JsonNode node = serialize();

        assertThat(node.get("title").asText()).isEqualTo("Example Title");
        assertThat(node.has("originalURL")).isFalse();
    }

    @Test
    public void whitelistMode_fieldMatchingIsCaseInsensitive() throws IOException {
        serializer.showIds = false;
        result.setFields(new String[] { "TITLE" });
        JsonNode node = serialize();
        assertThat(node.get("title").asText()).isEqualTo("Example Title");
    }

    @Test
    public void whitelistMode_idIsNotGatedByShowIds() throws IOException {
        // Unlike default mode, requesting "id" explicitly via the fields whitelist bypasses the showIds gate
        serializer.showIds = false;
        result.setFields(new String[] { "id" });
        JsonNode node = serialize();
        assertThat(node.get("id").asText()).isEqualTo("doc123");
    }
}
