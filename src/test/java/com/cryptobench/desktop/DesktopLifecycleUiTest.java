package com.cryptobench.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static com.cryptobench.desktop.DesktopUiTest.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Runs real desktop startup, native setup, browser navigation and task shutdown. */
@EnabledIfSystemProperty(named = "cryptobench.test.desktopLifecycle", matches = "true")
class DesktopLifecycleUiTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private URI base;

    @Test void nativeSetupAndConfirmedExitPreserveCompletedWorkAcrossRestart() throws Exception {
        assertThat(DesktopUi.available()).isTrue();
        String previousHome = System.getProperty("cryptobench.desktop.home");
        DesktopPaths paths = new DesktopPaths(temporary.resolve("desktop 中文 test"));
        System.setProperty("cryptobench.desktop.home", paths.home.toString());
        var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        CountDownLatch secondTask = new CountDownLatch(1), releaseAgent = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon().factory()));
        agent.createContext("/", exchange -> {
            try (exchange) {
                String route = exchange.getRequestURI().getPath();
                exchange.getRequestBody().readAllBytes();
                String body = switch (route) {
                    case "/health" -> "{\"status\":\"ok\"}";
                    case "/capabilities" -> "{\"protocolVersion\":\"1.0\",\"agentVersion\":\"desktop-fixture\",\"models\":[\"GPT-4.1\"],\"tools\":[\"python\"],\"datasets\":[{\"id\":\"mini-spn\",\"version\":\"1.0.0\"}]}";
                    default -> "{\"status\":\"completed\",\"artifact\":{\"answer\":\"active_sbox_bound=4\"},\"usage\":{\"agentCalls\":1}}";
                };
                if (route.equals("/analyze") && calls.incrementAndGet() == 2) {
                    secondTask.countDown();
                    try { releaseAgent.await(90, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException closedDuringShutdown) { /* The application cancels this request on exit. */ }
        });
        agent.start();
        String[] arguments = {"--server.port=0", "--benchmark.docker.enabled=false", "--benchmark.gateway.enabled=false",
                "--benchmark.mail.worker-enabled=false", "--benchmark.admin.bootstrap.email=", "--benchmark.admin.bootstrap.password=",
                "--server.tomcat.accesslog.enabled=true", "--server.tomcat.accesslog.buffered=false",
                "--server.tomcat.accesslog.directory=" + paths.logs.resolve("http"),
                "--server.tomcat.accesslog.pattern=%r %s %{User-Agent}i"};
        Future<Integer> application = null;
        try {
            application = worker.submit(() -> DesktopLauncher.launch(arguments));
            choose(dialog("CryptoBench 首次设置"), JOptionPane.CANCEL_OPTION);
            assertThat(application.get(30, TimeUnit.SECONDS)).isZero();
            assertThat(paths.run.resolve("instance.properties")).doesNotExist();

            application = worker.submit(() -> DesktopLauncher.launch(arguments));
            JDialog setup = dialog("CryptoBench 首次设置");
            fill(setup, "ui-admin@example.test");
            choose(setup, JOptionPane.OK_OPTION);
            awaitReady(paths);
            // No test HTTP requests precede this assertion. Browser GETs in the
            // access log prove that the actual registered system browser opened.
            await(() -> browserVisited(paths), "The system browser must load the desktop homepage");
            assertThat(DesktopLauncher.launch(new String[0])).isZero();
            login();
            JsonNode run = request("/api/runs", Map.of("source", "local", "endpoint", "http://127.0.0.1:" + agent.getAddress().getPort(),
                    "kind", "evaluation", "datasetId", "mini-spn", "version", "1.0.0", "scope", "sample", "budget", "standard",
                    "model", "GPT-4.1", "agentName", "Desktop exit fixture"), 202);
            String id = run.path("runId").asText(run.path("id").asText());
            assertThat(secondTask.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(request("/api/runs/" + id, null, 200).path("progress").path("completed").asInt()).isEqualTo(1);
            assertThat(DesktopInstance.request(paths.run, "stop", Duration.ofSeconds(3))).isEqualTo("BUSY");

            clickExit();
            choose(dialog("退出 CryptoBench"), JOptionPane.NO_OPTION);
            assertThat(application.isDone()).isFalse();
            clickExit();
            choose(dialog("退出 CryptoBench"), JOptionPane.YES_OPTION);
            assertThat(application.get(45, TimeUnit.SECONDS)).isZero();
            assertThat(paths.run.resolve("instance.properties")).doesNotExist();
            byte[] key = Files.readAllBytes(paths.data.resolve("secrets/mail.key"));
            releaseAgent.countDown();

            application = worker.submit(() -> DesktopLauncher.launch(arguments));
            awaitReady(paths);
            login();
            JsonNode restored = request("/api/runs/" + id, null, 200);
            assertThat(restored.path("status").asText()).isEqualTo("paused");
            assertThat(restored.path("progress").path("completed").asInt()).isEqualTo(1);
            assertThat(Files.readAllBytes(paths.data.resolve("secrets/mail.key"))).containsExactly(key);
            assertThat(DesktopInstance.request(paths.run, "stop", Duration.ofSeconds(3))).isEqualTo("STOPPING");
            assertThat(application.get(30, TimeUnit.SECONDS)).isZero();
            Files.writeString(Path.of("target/desktop-lifecycle-ui-evidence.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "os", System.getProperty("os.name"), "java", Runtime.version().toString(),
                    "nativeAdminSetupCancelAndRetry", true, "systemBrowserHttpNavigation", true,
                    "repeatedLaunch", true, "busyStopRejected", true, "cancelExitKeptRunning", true,
                    "confirmedExit", true, "restoredRunStatus", restored.path("status").asText(),
                    "restoredCompletedTasks", restored.path("progress").path("completed").asInt())));
        } finally {
            releaseAgent.countDown();
            new DesktopUi().close();
            if (application != null && !application.isDone()) {
                try { DesktopInstance.request(paths.run, "stop", Duration.ofSeconds(5)); application.get(35, TimeUnit.SECONDS); }
                catch (Exception ignored) { application.cancel(true); }
            }
            agent.stop(0);
            worker.shutdownNow();
            // A real desktop JVM exits here. This in-process test must explicitly
            // release Logback's file handle before JUnit removes the temporary home.
            ((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).stop();
            if (previousHome == null) System.clearProperty("cryptobench.desktop.home");
            else System.setProperty("cryptobench.desktop.home", previousHome);
        }
    }

    private void awaitReady(DesktopPaths paths) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            try {
                String state = DesktopInstance.request(paths.run, "status", Duration.ofMillis(300));
                if (state.startsWith("READY ")) { base = URI.create(state.substring(6)); return; }
            } catch (IOException starting) { /* Startup has not created its descriptor yet. */ }
            Thread.sleep(100);
        }
        throw new AssertionError("Desktop did not become ready");
    }

    private static boolean browserVisited(DesktopPaths paths) {
        Path directory = paths.logs.resolve("http");
        if (!Files.isDirectory(directory)) return false;
        try (var logs = Files.list(directory)) {
            return logs.anyMatch(file -> {
                try { return Files.readString(file).lines().anyMatch(line -> line.startsWith("GET / HTTP/") && line.contains("Mozilla/")); }
                catch (IOException unavailable) { return false; }
            });
        } catch (IOException unavailable) { return false; }
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as(message).isTrue();
    }

    private static void clickExit() {
        EventQueue.invokeLater(() -> {
            if (SystemTray.isSupported()) for (TrayIcon icon : SystemTray.getSystemTray().getTrayIcons()) {
                if (!"CryptoBench 正在运行".equals(icon.getToolTip())) continue;
                PopupMenu menu = icon.getPopupMenu();
                for (int index = 0; index < menu.getItemCount(); index++) {
                    MenuItem item = menu.getItem(index);
                    if (item.getLabel().equals("退出")) {
                        for (var listener : item.getActionListeners()) listener.actionPerformed(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, "退出"));
                        return;
                    }
                }
            }
            for (Window window : Window.getWindows()) if (window instanceof JFrame frame && frame.isShowing() && frame.getTitle().equals("CryptoBench 正在运行")) {
                frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING)); return;
            }
            throw new AssertionError("Desktop exit control was not available");
        });
    }

    private void login() throws Exception {
        request("/api/auth/login", Map.of("email", "ui-admin@example.test", "password", "ui-fixture-password"), 200);
    }

    private JsonNode request(String path, Object body, int status) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10));
        if (body != null) {
            JsonNode csrf = request("/api/auth/csrf", null, 200);
            builder.header(csrf.path("headerName").asText(), csrf.path("token").asText()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }
}
