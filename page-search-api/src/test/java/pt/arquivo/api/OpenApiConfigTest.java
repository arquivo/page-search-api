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
}
