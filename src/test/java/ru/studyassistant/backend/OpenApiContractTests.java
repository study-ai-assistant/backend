package ru.studyassistant.backend;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTests {
    @Test
    void openApiIsValidAndLocalReferencesResolve() {
        var options = new ParseOptions();
        options.setResolve(true);
        var result = new OpenAPIV3Parser().readLocation("docs/api/openapi.yaml", null, options);
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getOpenAPI().getPaths()).containsKeys("/health", "/api/v1/text/process");
    }
}
