package com.cryptobench.desktop;

import com.cryptobench.benchmark.CryptoBenchApplication;
import com.cryptobench.benchmark.auth.service.DesktopAdminService;
import com.cryptobench.benchmark.persistence.MybatisDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point used by the optional Windows app-image. The normal Spring Boot
 * entry point remains unchanged; this launcher only opens the local web UI
 * after the embedded server has become ready.
 */
public final class DesktopLauncher {
    private static final Logger log = LoggerFactory.getLogger(DesktopLauncher.class);

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        int code = launch(args);
        if (code != 0) System.exit(code);
    }

    static int launch(String[] args) {
        DesktopPaths paths = null;
        try {
            paths = DesktopPaths.installed();
            String command = Arrays.stream(args).filter(value -> value.startsWith("--desktop-command="))
                    .map(value -> value.substring("--desktop-command=".length())).findFirst().orElse(null);
            if (command != null) {
                String response = DesktopInstance.request(paths.run, command, Duration.ofSeconds(command.equals("status") ? 1 : 60));
                System.out.println(response);
                return response.startsWith("READY") || response.equals("STARTING") || response.equals("OPENED") || response.equals("STOPPING") ? 0 : 2;
            }
            paths.prepare();
            try (DesktopInstance instance = DesktopInstance.acquire(paths.run)) {
                if (instance == null) {
                    String response = DesktopInstance.request(paths.run, "open", Duration.ofSeconds(60));
                    if (!response.equals("OPENED")) throw new IllegalStateException("已有实例未能打开页面：" + response);
                    return 0;
                }
                return runOwned(paths, instance, args);
            }
        } catch (Exception exception) {
            if (exception instanceof SetupCancelled || hasCause(exception, SetupCancelled.class)) return 0;
            reportFailure(paths, exception);
            return 1;
        }
    }

    private static int runOwned(DesktopPaths paths, DesktopInstance instance, String[] args) throws Exception {
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        AtomicBoolean stopping = new AtomicBoolean();
        CountDownLatch stopped = new CountDownLatch(1);
        try (DesktopUi ui = new DesktopUi()) {
            Runnable close = () -> {
                if (!stopping.compareAndSet(false, true)) return;
                Thread.ofPlatform().name("desktop-shutdown").start(() -> {
                    try { if (context.get() != null) context.get().close(); }
                    finally { ui.close(); stopped.countDown(); }
                });
            };
            Thread hook = new Thread(() -> { if (context.get() != null) context.get().close(); }, "desktop-exit");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                SpringApplication application = application(ready -> {
                    context.set(ready);
                    ready.getBean(com.cryptobench.benchmark.mail.MailCipher.class).checkReady();
                    DesktopAdminService admins = ready.getBean(DesktopAdminService.class);
                    if (!DesktopUi.initializeAdmin(admins)) throw new SetupCancelled();
                    URI uri = desktopUri(ready);
                    log.info("Desktop UI ready: {}", uri);
                    log.info("Desktop version {} using Java {}; data directory {}", System.getProperty("jpackage.app-version", "development"), Runtime.version(), paths.data);
                    Runnable open = () -> openBrowser(uri, DesktopUi::browse, true);
                    Runnable stop = () -> {
                        Integer active = ready.getBean(MybatisDatabase.class).queryForObject("SELECT COUNT(*) FROM benchmark_runs WHERE status IN ('queued','running')", Integer.class);
                        if (active == null || active == 0 || DesktopUi.confirmStop()) close.run();
                    };
                    instance.onCommand(command -> {
                        if (stopping.get()) return "STOPPING";
                        if (command.equals("status")) return "READY " + uri;
                        if (command.equals("open")) { java.awt.EventQueue.invokeLater(open); return "OPENED"; }
                        Integer active = ready.getBean(MybatisDatabase.class).queryForObject("SELECT COUNT(*) FROM benchmark_runs WHERE status IN ('queued','running')", Integer.class);
                        if (active != null && active > 0) return "BUSY";
                        close.run(); return "STOPPING";
                    });
                    ui.show(paths, open, stop);
                    open.run();
                });
                String home = paths.home.toString().replace('\\', '/');
                // The packaged application is always local; command-line profile/address overrides
                // must not accidentally turn a desktop control surface into a network deployment.
                application.addListeners(new DesktopEnvironment(paths));
                application.setAdditionalProfiles("desktop");
                var defaults = new HashMap<String, Object>();
                defaults.put("cryptobench.desktop.home", home);
                defaults.put("spring.config.additional-location", "optional:" + paths.data.resolve("config/desktop.properties").toUri());
                application.setDefaultProperties(defaults);
                context.set(application.run(args));
                stopped.await();
                return 0;
            } catch (RuntimeException exception) {
                if (stopping.get()) return 0;
                throw exception;
            } finally {
                if (context.get() != null) context.get().close();
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException exiting) { /* JVM shutdown */ }
            }
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true;
        return false;
    }

    private static void reportFailure(DesktopPaths paths, Exception exception) {
        StringWriter trace = new StringWriter();
        exception.printStackTrace(new PrintWriter(trace));
        String diagnostic = DesktopRedactionConverter.redact(trace.toString());
        Path file = paths == null ? null : paths.logs.resolve("startup-failure.log");
        try {
            try { if (file == null) throw new java.io.IOException(); Files.writeString(file, diagnostic); }
            catch (Exception unavailable) { file = Files.createTempFile("cryptobench-startup-", ".log"); Files.writeString(file, diagnostic); }
        } catch (Exception unavailable) { file = null; }
        String reason = "启动失败，请检查目录写入权限、数据库和配置。";
        if (hasCause(exception, org.springframework.boot.web.server.PortInUseException.class) || hasCause(exception, java.net.BindException.class)) reason = "端口已被占用，请先关闭其他 CryptoBench 实例或调整端口配置。";
        if (hasCause(exception, org.flywaydb.core.api.FlywayException.class)) reason = "数据库升级失败。请保留现有数据，并根据日志排查；不要删除数据库。";
        DesktopUi.startupError(reason + "\n" + (file == null ? "无法写入诊断文件。" : "诊断文件：" + file),
                file == null ? null : file.getParent());
    }

    private static final class SetupCancelled extends RuntimeException { }

    @FunctionalInterface
    interface BrowserOpener {
        void open(URI uri) throws Exception;
    }

    static ConfigurableApplicationContext start(String[] args, BrowserOpener browser) {
        return application(context -> {
            URI uri = desktopUri(context);
            log.info("Desktop UI ready: {}", uri);
            openBrowser(uri, browser, false);
        }).run(args);
    }

    private static SpringApplication application(Consumer<ConfigurableApplicationContext> ready) {
        SpringApplication application = new SpringApplication(CryptoBenchApplication.class);
        application.setHeadless(false);
        AtomicBoolean opened = new AtomicBoolean();
        application.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> {
            if (opened.compareAndSet(false, true)) ready.accept(event.getApplicationContext());
        });
        return application;
    }

    private static URI desktopUri(ConfigurableApplicationContext context) {
        String address = context.getEnvironment().getProperty("server.address", "127.0.0.1");
        if (address == null || address.isBlank() || "0.0.0.0".equals(address) || "::".equals(address)) {
            address = "127.0.0.1";
        }
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        if (address.contains(":") && !address.startsWith("[")) address = "[" + address + "]";
        String basePath = context.getEnvironment().getProperty("server.servlet.context-path", "");
        return URI.create("http://" + address + ":" + port + basePath + "/#/");
    }

    private static void openBrowser(URI uri, BrowserOpener browser, boolean visible) {
        try {
            browser.open(uri);
        } catch (Exception exception) {
            log.warn("Unable to open the system browser; open {} manually ({})", uri, exception.toString());
            if (visible) DesktopUi.error("无法打开默认浏览器，服务仍在运行。请手动访问：\n" + uri);
        }
    }
}
