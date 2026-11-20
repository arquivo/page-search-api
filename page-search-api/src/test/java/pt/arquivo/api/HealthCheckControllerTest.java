package pt.arquivo.api;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PageSearchApplication.class)
@AutoConfigureMockMvc
public class HealthCheckControllerTest {

    @MockitoBean
    private SolrClient healthCheckSolrClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void healthcheckSolrReachable() throws Exception {
        Mockito.when(healthCheckSolrClient.ping()).thenReturn(null);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch/healthcheck")).andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"solr\":\"ok\"}");
    }

    @Test
    public void healthcheckSolrUnreachable() throws Exception {
        Mockito.when(healthCheckSolrClient.ping())
                .thenThrow(new SolrServerException("Connection refused to solr-internal.arquivo.pt:8983"));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch/healthcheck")).andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo("{\"solr\":\"unreachable\"}");
    }

    @Test
    public void healthcheckSolrIOException() throws Exception {
        Mockito.when(healthCheckSolrClient.ping()).thenThrow(new IOException("timeout"));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/textsearch/healthcheck")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
    }
}
