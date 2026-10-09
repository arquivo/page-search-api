package pt.arquivo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import pt.arquivo.services.SearchQueryImpl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PageSearchApplication.class)
@AutoConfigureMockMvc
@RunWith(SpringRunner.class)
public class OpenApiConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void apiDocsPublishRelativeServerUrl() throws Exception {
        // Request through a servlet context path, as in Tomcat, to check springdoc doesn't replace the configured
        // server with one generated from the host plus context path (e.g. http://localhost/pagesearch).
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders
                        .get("/pagesearch/textsearch/api-docs/v3")
                        .contextPath("/pagesearch"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JSONArray servers = new JSONObject(response.getContentAsString()).getJSONArray("servers");
        assertThat(servers.length()).isEqualTo(1);
        assertThat(servers.getJSONObject(0).getString("url")).isEqualTo("/");
    }

    @Test
    public void apiDocsDescribeTheService() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders
                        .get("/textsearch/api-docs/v3"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        // application/json carries no charset, so MockMvc would otherwise decode it as ISO-8859-1
        JSONObject info = new JSONObject(response.getContentAsString(StandardCharsets.UTF_8)).getJSONObject("info");
        assertThat(info.getString("title")).isEqualTo("Arquivo.pt Full-text Search API");
        // Read from openapi-description.md, so checks the file is found on the classpath and read whole, in UTF-8
        assertThat(info.getString("description"))
                .startsWith("Full-text search over the web pages and documents preserved by [Arquivo.pt]")
                .contains("q=\"António Costa\"")
                .contains("can be requested cross-origin from a browser.");
    }

    @Test
    public void apiDocsListSpellcheckAsFieldsValue() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders
                        .get("/textsearch/api-docs/v3"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JSONArray parameters = new JSONObject(response.getContentAsString(StandardCharsets.UTF_8))
                .getJSONObject("paths").getJSONObject("/textsearch").getJSONObject("get").getJSONArray("parameters");
        JSONObject fields = null;
        for (int i = 0; i < parameters.length(); i++) {
            if ("fields".equals(parameters.getJSONObject(i).getString("name"))) {
                fields = parameters.getJSONObject(i);
            }
        }
        assertThat(fields).isNotNull();
        // Clients validating against the enum would otherwise reject fields=spellcheck, which the API accepts
        JSONArray allowedValues = fields.getJSONObject("schema").getJSONObject("items").getJSONArray("enum");
        List<String> allowed = new ArrayList<>();
        for (int i = 0; i < allowedValues.length(); i++) {
            allowed.add(allowedValues.getString(i));
        }
        assertThat(allowed).contains("title", "spellcheck");
        assertThat(fields.getString("description")).contains("suggested_query");
    }

    @Test
    public void apiDocsDescribeTheTextsearchResponses() throws Exception {
        JSONObject apiDocs = getApiDocs();

        // /textsearch replies PageSearchResponse for q and versionHistory, MetadataResponse for metadata
        JSONArray textsearchReplies = apiDocs.getJSONObject("paths").getJSONObject("/textsearch").getJSONObject("get")
                .getJSONObject("responses").getJSONObject("200").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema").getJSONArray("anyOf");
        assertThat(refs(textsearchReplies))
                .containsExactly("#/components/schemas/PageSearchResponse", "#/components/schemas/MetadataResponse");

        // A response type hidden from springdoc (e.g. by @Hidden on ApiResponse) leaves the 200 reply without content
        assertThat(apiDocs.getJSONObject("paths").getJSONObject("/metadata").getJSONObject("get")
                .getJSONObject("responses").getJSONObject("200").getJSONObject("content").getJSONObject("*/*")
                .getJSONObject("schema").getString("$ref"))
                .isEqualTo("#/components/schemas/MetadataResponse");

        JSONObject schemas = apiDocs.getJSONObject("components").getJSONObject("schemas");
        assertThat(schemas.getJSONObject("PageSearchResponse").getJSONObject("properties").getJSONObject("response_items")
                .getJSONObject("items").getString("$ref")).isEqualTo("#/components/schemas/SearchResult");
        assertThat(schemas.getJSONObject("MetadataResponse").getJSONObject("properties").getJSONObject("response_items")
                .getJSONObject("items").getString("$ref")).isEqualTo("#/components/schemas/SearchResult");
        assertEveryPropertyDescribed(schemas.getJSONObject("PageSearchResponse"));
        assertEveryPropertyDescribed(schemas.getJSONObject("MetadataResponse"));
    }

    @Test
    public void apiDocsDescribeEveryResultField() throws Exception {
        JSONObject apiDocs = getApiDocs();

        JSONObject searchResult = apiDocs.getJSONObject("components").getJSONObject("schemas").getJSONObject("SearchResult");
        assertEveryPropertyDescribed(searchResult);

        // The result fields a user can ask for in fields are the ones documented, and nothing internal to the
        // result classes (solrClient, timeAllowed, hostKey, ...) is. spellcheck is a fields value but not a result field.
        JSONArray parameters = apiDocs.getJSONObject("paths").getJSONObject("/textsearch").getJSONObject("get")
                .getJSONArray("parameters");
        List<String> selectableFields = new ArrayList<>();
        for (int i = 0; i < parameters.length(); i++) {
            JSONObject parameter = parameters.getJSONObject(i);
            if ("fields".equals(parameter.getString("name"))) {
                JSONArray allowedValues = parameter.getJSONObject("schema").getJSONObject("items").getJSONArray("enum");
                for (int j = 0; j < allowedValues.length(); j++) {
                    selectableFields.add(allowedValues.getString(j));
                }
            }
        }
        selectableFields.remove("spellcheck");
        assertThat(keys(searchResult.getJSONObject("properties"))).containsExactlyInAnyOrderElementsOf(selectableFields);
    }

    @Test
    public void apiDocsDescribeRequestParametersAsSerialized() throws Exception {
        JSONObject searchQuery = getApiDocs().getJSONObject("components").getJSONObject("schemas")
                .getJSONObject("SearchQuery");

        // Every property set, so the JSON carries each one request_parameters can reply
        SearchQueryImpl query = new SearchQueryImpl("sapo");
        query.setFrom("2001");
        query.setTo("2010");
        query.setType(new String[]{"pdf"});
        query.setSite(new String[]{"sapo.pt"});
        query.setCollection(new String[]{"FAWP*"});
        query.setFields(new String[]{"title"});
        query.setTitleSearch("sapo");
        query.setTimeline(true);
        query.setYearBalance(0.5);
        query.setLanguage("pt");
        query.setMinLanguageConfidence("LOW");
        JSONObject serialized = new JSONObject(objectMapper.writeValueAsString(query));

        assertThat(keys(searchQuery.getJSONObject("properties")))
                .containsExactlyInAnyOrderElementsOf(keys(serialized));
    }

    private JSONObject getApiDocs() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders
                        .get("/textsearch/api-docs/v3"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return new JSONObject(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** Properties that $ref another schema carry their description on that schema instead. */
    private static void assertEveryPropertyDescribed(JSONObject schema) throws Exception {
        JSONObject properties = schema.getJSONObject("properties");
        for (String name : keys(properties)) {
            JSONObject property = properties.getJSONObject(name);
            if (!property.has("$ref")) {
                assertThat(property.optString("description")).as("description of %s", name).isNotEmpty();
            }
        }
    }

    private static List<String> keys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        return keys;
    }

    private static List<String> refs(JSONArray schemas) throws Exception {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < schemas.length(); i++) {
            refs.add(schemas.getJSONObject(i).getString("$ref"));
        }
        return refs;
    }
}
