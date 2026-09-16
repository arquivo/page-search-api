package pt.arquivo.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class OpenApiConfig {
    /* https://springdoc.org/ */

    @Value("${searchpages.api.title.maxlength:300}")
    private int defaultTitleMaxLength;

    @Bean
    public OpenAPI api() {
        return new OpenAPI()
                .tags(Arrays.asList(
                        new Tag().name("PageSearch").description("Endpoints to search for Archived WebPages content"),
                        new Tag().name("Metadata").description("(Not Published) Endpoints to retrieve metadata information about an Archived Web Resource"),
                        new Tag().name("HealthCheck").description("Endpoints to check the connectivity of this service's dependencies")
                ));
    }

    // The @Parameter description on titleMaxLength can't reference the injected default directly since annotation
    // attributes must be compile-time constants, so the actual configured value is spliced in here at doc-generation
    // time instead.
    @Bean
    public OperationCustomizer titleMaxLengthDefaultDocCustomizer() {
        return (operation, handlerMethod) -> {
            if (operation.getParameters() != null) {
                operation.getParameters().stream()
                        .filter(parameter -> "titleMaxLength".equals(parameter.getName()))
                        .forEach(parameter -> parameter.setDescription(
                                parameter.getDescription() + " Defaults to " + defaultTitleMaxLength + "."));
            }
            return operation;
        };
    }
}
