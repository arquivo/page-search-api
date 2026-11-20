package pt.arquivo.api.exceptions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

public class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    public void handleApiRequestException_returnsBadRequestWithMessage() {
        ResponseEntity<Object> response = handler.handleApiRequestException(new ApiRequestException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiException body = (ApiException) response.getBody();
        assertThat(body.getMessage()).isEqualTo("bad input");
        assertThat(body.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(body.getTimestamp()).isNotNull();
    }

    @Test
    public void handleApiNotFoundResourceException_returnsNotFoundWithMessage() {
        ResponseEntity<Object> response = handler
                .handleApiNotFoundResourceException(new ApiNotFoundResourceException("missing resource"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ApiException body = (ApiException) response.getBody();
        assertThat(body.getMessage()).isEqualTo("missing resource");
        assertThat(body.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(body.getTimestamp()).isNotNull();
    }

    @Test
    public void handleUnexpectedException_returnsInternalServerErrorWithGenericMessage() {
        ResponseEntity<Object> response = handler
                .handleUnexpectedException(new RuntimeException("solr://internal-host:8983 connection failed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiException body = (ApiException) response.getBody();
        assertThat(body.getMessage()).doesNotContain("internal-host");
        assertThat(body.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(body.getTimestamp()).isNotNull();
    }
}
