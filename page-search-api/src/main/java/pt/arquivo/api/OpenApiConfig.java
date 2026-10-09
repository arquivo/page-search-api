package pt.arquivo.api;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class OpenApiConfig {
    /* https://springdoc.org/ */

    @Value("${searchpages.api.title.maxlength:300}")
    private int defaultTitleMaxLength;

    @Value("${searchpages.api.snippet.maxlength:300}")
    private int defaultSnippetMaxLength;

    // Without explicit servers springdoc generates one from the request host plus the servlet context path
    // (e.g. https://arquivo.pt/pagesearch), which 404s behind Apache where the API is published at /textsearch.
    // A relative URL is resolved by clients against the URL the spec was loaded from.
    @Value("${searchpages.api.openapi.server.url:/}")
    private String openApiServerUrl;

    // Markdown, kept in its own file rather than in a string literal so it can be read and edited as a document
    @Value("classpath:openapi-description.md")
    private Resource apiDescription;

    @Bean
    public OpenAPI api() throws IOException {
        return new OpenAPI()
                .info(new Info()
                        .title("Arquivo.pt Full-text Search API")
                        .version("1.0")
                        .description(StreamUtils.copyToString(apiDescription.getInputStream(), StandardCharsets.UTF_8))
                        .license(new License()
                                .name("GPL-3.0")
                                .url("https://github.com/arquivo/page-search-api/blob/master/LICENSE")))
                .externalDocs(new ExternalDocumentation()
                        .description("Arquivo.pt API documentation")
                        .url("https://arquivo.pt/api"))
                .servers(Collections.singletonList(new Server().url(openApiServerUrl)))
                .tags(Arrays.asList(
                        new Tag().name("PageSearch").description("Endpoints to search for Archived WebPages content"),
                        new Tag().name("Metadata").description("(Not Published) Endpoints to retrieve metadata information about an Archived Web Resource"),
                        new Tag().name("HealthCheck").description("Endpoints to check the connectivity of this service's dependencies")
                ));
    }

    // The @Parameter description on these parameters can't reference the injected defaults directly since annotation
    // attributes must be compile-time constants, so the actual configured values are spliced in here at
    // doc-generation time instead.
    @Bean
    public OperationCustomizer defaultValueDocCustomizer() {
        Map<String, Integer> defaultsByParameter = new HashMap<>();
        defaultsByParameter.put("titleMaxLength", defaultTitleMaxLength);
        defaultsByParameter.put("snippetMaxLength", defaultSnippetMaxLength);

        return (operation, handlerMethod) -> {
            if (operation.getParameters() != null) {
                operation.getParameters().stream()
                        .filter(parameter -> defaultsByParameter.containsKey(parameter.getName()))
                        .forEach(parameter -> parameter.setDescription(
                                parameter.getDescription() + " Defaults to "
                                        + defaultsByParameter.get(parameter.getName()) + "."));
            }
            return operation;
        };
    }
}
