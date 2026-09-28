package com.example.agent.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI / Swagger UI metadata for the Review Agent API.
 *
 * <p>springdoc auto-discovers the REST controllers and generates the spec; this
 * bean only supplies the top-level info block and a local server entry so the
 * Swagger UI "Try it out" button targets the running instance.
 *
 * <ul>
 *   <li>OpenAPI JSON : {@code /v3/api-docs}</li>
 *   <li>Swagger UI   : {@code /swagger-ui.html}</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI reviewAgentOpenAPI(@Value("${server.port:8090}") int serverPort) {
        Server local = new Server()
                .url("http://localhost:" + serverPort)
                .description("Local development server");

        return new OpenAPI()
                .info(new Info()
                        .title("Review Agent API")
                        .description("""
                                REST API for the grants document Review Agent.

                                Includes the reservation index summarization endpoint, which folds
                                a reservation's question/answer pairs into a single lossless summary
                                using the LLM and stores it (status + reservationId + summary).""")
                        .version("1.0")
                        .contact(new Contact().name("Grants AI Service"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(local));
    }
}
