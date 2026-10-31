package com.tamada.travel.orchestrator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("T-Amada Travel Orchestrator API")
                        .version("1.0.0")
                        .description("""
                                Сервис оркестрации составного заказа путешествия (паттерн Saga).
                                
                                ### Возможности:
                                - **Создание составных заказов** (авиабилеты, отели, страховки, трансферы).
                                - **Последовательное бронирование**: `FLIGHT` -> `HOTEL` -> `INSURANCE` -> `TRANSFER`.
                                - **Автоматическая компенсация**: отмена уже забронированных ресурсов при ошибке на промежуточном шаге.
                                - **Ручная отмена заказа**.
                                
                                ### Жизненный цикл заказа:
                                `NEW` -> `PROCESSING` -> `COMPLETED`  
                                `NEW` / `PROCESSING` / `COMPLETED` -> `CANCELLING` -> `CANCELLED`  
                                `PROCESSING` (при ошибке) -> `FAILED` -> `CANCELLING` -> `CANCELLED`
                                """)
                        .contact(new Contact()
                                .name("FEZUGICRIX")
                                .url("https://github.com/FEZUGICRIX/t-amada-orchestrator")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Локальный сервер разработки")
                ));
    }
}
