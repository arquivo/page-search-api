package pt.arquivo.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collections;

/**
 * Checks the Solr backend, to gate rolling deploys of this service.
 */
@Tag(name = "HealthCheck")
@RestController
public class HealthCheckController {

    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckController.class);

    @Autowired
    private SolrClient healthCheckSolrClient;

    @Operation(summary = "Check connectivity to the configured Solr instance")
    @GetMapping(value = "/textsearch/healthcheck")
    public ResponseEntity<Object> healthcheck() {
        try {
            healthCheckSolrClient.ping();
            return new ResponseEntity<>(Collections.singletonMap("solr", "ok"), HttpStatus.OK);
        } catch (SolrServerException | IOException e) {
            LOG.error("Solr healthcheck failed: ", e);
            return new ResponseEntity<>(Collections.singletonMap("solr", "unreachable"), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
