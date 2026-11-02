package com.cryptobench.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.context.WebServerApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class DesktopLauncherTest {
    private String[] args() {
        return new String[]{"--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=test",
                "--spring.datasource.url=jdbc:h2:mem:desktop-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "--benchmark.demo-enabled=false", "--benchmark.legacy-import-enabled=false",
                "--benchmark.docker.enabled=false", "--benchmark.gateway.enabled=false", "--benchmark.mail.worker-enabled=false",
                "--benchmark.mail.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "--benchmark.admin.bootstrap.email=", "--benchmark.admin.bootstrap.password="};
    }

    private int status(URI uri) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void opensActualBoundUrlOnceAfterServerIsReady(CapturedOutput output) throws Exception {
        AtomicReference<URI> opened = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        try (var context = DesktopLauncher.start(args(), uri -> {
            calls.incrementAndGet();
            opened.set(uri);
            assertThat(uri.getPort()).isPositive();
            assertThat(status(uri.resolve("/actuator/health"))).isEqualTo(200);
        })) {
            assertThat(opened.get().getPort()).isEqualTo(((WebServerApplicationContext) context).getWebServer().getPort());
            assertThat(opened.get().toString()).endsWith("/#/");
            context.publishEvent(new ApplicationReadyEvent(new org.springframework.boot.SpringApplication(),
                    new String[0], context, Duration.ZERO));
            assertThat(calls).hasValue(1);
            assertThat(output).contains("Desktop UI ready: " + opened.get());
        }
    }

    @Test
    void failedBrowserStillLeavesAHealthyServerAndLogsUrl(CapturedOutput output) throws Exception {
        AtomicReference<URI> opened = new AtomicReference<>();
        try (var context = DesktopLauncher.start(args(), uri -> {
            opened.set(uri);
            throw new UnsupportedOperationException("fixture browser unavailable");
        })) {
            assertThat(context.isActive()).isTrue();
            assertThat(status(opened.get().resolve("/actuator/health"))).isEqualTo(200);
            assertThat(output).contains("open " + opened.get() + " manually", "fixture browser unavailable");
        }
    }
}
