package com.cryptobench.benchmark;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cryptobench-production-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=test"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionSecurityIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void productionRejectsAdminDemoLoginEvenWithValidCsrf() throws Exception {
        mvc.perform(post("/api/auth/demo").with(csrf()).contentType("application/json")
                .content("{\"role\":\"admin\"}")).andExpect(status().isForbidden());
    }
}
