package com.tamada.travel.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnOpenApiJsonWithFullDocumentation() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isString())
                .andExpect(jsonPath("$.info.title").value("T-Amada Travel Orchestrator API"))
                .andExpect(jsonPath("$.paths['/api/v1/orders']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{id}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{id}/start'].post.responses['409']").exists())
                .andExpect(jsonPath("$.components.schemas['CreateOrderRequest']").exists())
                .andExpect(jsonPath("$.components.schemas['OrderResponse']").exists())
                .andExpect(jsonPath("$.components.schemas['ErrorResponse']").exists());
    }

    @Test
    void shouldReturnSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
