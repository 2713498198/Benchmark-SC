package com.cryptobench.benchmark;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.execution.service.ServerCostCalculator;
import com.cryptobench.benchmark.pricing.repository.PricingRepository;
import com.cryptobench.benchmark.pricing.service.PricingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pricing-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PricingLifecycleIntegrationTest {
    @Autowired PricingService service;
    @Autowired PricingRepository repository;
    @Autowired ServerCostCalculator calculator;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    private BenchmarkUser actor;

    @BeforeEach
    void admin() {
        String id = "price-admin-" + UUID.randomUUID();
        actor = new BenchmarkUser(id, id + "@example.com", "Price administrator", "admin", "unused", null, Instant.now().toString());
        jdbc.update("INSERT INTO benchmark_users(id,email,display_name,user_role,password_hash,created_at) VALUES(?,?,?,?,?,?)",
                actor.id(), actor.email(), actor.name(), actor.role(), actor.passwordHash(), actor.createdAt());
    }

    @Test
    void savedVersionsAreImmutableAndSnapshotSurvivesActivationAndJsonRoundTrip() throws Exception {
        String a = version(), b = version();
        service.create(actor, a, List.of(price("test-model", "0.50000000", "1.00000000"), price("other", "2", "4")));
        service.activate(actor, a, repository.state(false).revision(), 0L);
        ObjectNode snapshot = calculator.snapshot();
        var response = mapper.readTree("{\"usage\":{\"inputTokens\":1,\"outputTokens\":0}}");
        assertThat(calculator.calculate(snapshot, "test_model", response)).isEqualTo(0.000001);
        service.create(actor, b, List.of(price("test model", "20", "40")));
        var diff = service.preview(b);
        assertThat(diff.changes()).anyMatch(c -> c.kind().equals("removed") && c.model().equals("other"));
        service.activate(actor, b, diff.expectedRevision(), diff.expectedTableRevision());
        ObjectNode restored = (ObjectNode) mapper.readTree(mapper.writeValueAsBytes(snapshot));
        assertThat(restored.path("prices").get(1).path("inputPerMillionTokens").isTextual()).isTrue();
        assertThat(calculator.calculate(restored, "test model", response)).isEqualTo(0.000001);
        assertThat(calculator.calculate("test model", response)).isEqualTo(0.00002);
        restored.withArray("prices").removeAll();
        assertThat(calculator.snapshot().path("prices")).hasSize(1);
        var auth = new UsernamePasswordAuthenticationToken(actor, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(mvc.perform(put("/api/admin/pricing/" + a).with(authentication(auth)).with(csrf())
                .contentType("application/json").content("{\"prices\":[]}")).andReturn().getResponse().getStatus()).isIn(404, 405);
        assertThat(repository.prices(a)).hasSize(2);
    }

    @Test
    void validatesUsdPrecisionNormalizationAndRollsBackInvalidVersions() {
        for (var inputs : List.of(
                List.of(new PricingService.ModelPriceInput("x", BigDecimal.ONE, BigDecimal.ONE, "CNY")),
                List.of(price("x", "0.000000001", "1")),
                List.of(price("x", "1000000000000", "1")),
                List.of(price("x", "-0.1", "1")),
                List.of(price("a-b", "1", "1"), price(" A_B ", "2", "2")))) {
            String version = version();
            assertThatThrownBy(() -> service.create(actor, version, inputs)).isInstanceOf(ApiException.class);
            assertThat(repository.exists(version)).isFalse();
        }
        String version = version();
        service.create(actor, version, List.of(price("precise", "999999999999.99999999", "0.00000001")));
        assertThat(repository.prices(version).getFirst().inputPerMillionTokens()).isEqualByComparingTo("999999999999.99999999");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_audits WHERE actor_user_id=? AND action='create_pricing'", Integer.class, actor.id())).isEqualTo(1);
    }

    @Test
    void exactlyOneConcurrentActivationWinsAndStalePreviewIsConflict() throws Exception {
        String a = version(), b = version();
        service.create(actor, a, List.of(price("x", "1", "2")));
        service.create(actor, b, List.of(price("x", "3", "4")));
        long revision = repository.state(false).revision();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return activation(a, revision); });
            var second = pool.submit(() -> { gate.await(); return activation(b, revision); });
            gate.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(repository.state(false).revision()).isEqualTo(revision + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_price_tables WHERE status='active'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_audits WHERE actor_user_id=? AND action='activate_pricing'", Integer.class, actor.id())).isEqualTo(1);
    }

    @Test
    void legacyNonUsdPricesNeverProduceUsdCost() throws Exception {
        String legacy = version();
        repository.insertTable(legacy, "draft", actor.id(), Instant.now().toString());
        repository.insertPrice(legacy, "legacy model", new BigDecimal("9"), BigDecimal.ONE, "CNY");
        assertThatThrownBy(() -> service.activate(actor, legacy, repository.state(false).revision(), 0L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        // Simulates a version left active by the previous release, using only this isolated test database.
        jdbc.update("UPDATE benchmark_pricing_state SET active_version=?, revision=revision+1 WHERE singleton_id=1", legacy);
        var snapshot = calculator.snapshot();
        var response = mapper.readTree("{\"usage\":{\"inputTokens\":1000,\"outputTokens\":1000}}");
        assertThat(calculator.costStatus(snapshot, "legacy-model", response)).isEqualTo("unsupported_currency");
        assertThat(calculator.calculate(snapshot, "legacy-model", response)).isNull();
        assertThat(service.active().prices().getFirst().currency()).isEqualTo("CNY");
    }

    private int activation(String version, long revision) {
        try { service.activate(actor, version, revision, 0L); return 200; }
        catch (ApiException exception) { return exception.getStatus().value(); }
    }
    private static String version() { return "price-" + UUID.randomUUID(); }
    private static PricingService.ModelPriceInput price(String model, String input, String output) {
        return new PricingService.ModelPriceInput(model, new BigDecimal(input), new BigDecimal(output), "USD");
    }
}
