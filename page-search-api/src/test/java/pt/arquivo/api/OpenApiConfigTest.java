package pt.arquivo.api;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PageSearchApplication.class)
@AutoConfigureMockMvc
@RunWith(SpringRunner.class)
public class OpenApiConfigTest {

    @Autowired
    private MockMvc mockMvc;

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
}
