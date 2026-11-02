package com.cryptobench.desktop;

import com.cryptobench.benchmark.CryptoBenchApplication;
import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
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
        start(args, uri -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new UnsupportedOperationException("System browser integration is unavailable");
            }
            Desktop.getDesktop().browse(uri);
        });
    }

    @FunctionalInterface
    interface BrowserOpener {
        void open(URI uri) throws Exception;
    }

    static ConfigurableApplicationContext start(String[] args, BrowserOpener browser) {
        SpringApplication application = new SpringApplication(CryptoBenchApplication.class);
        application.setHeadless(false);
        AtomicBoolean opened = new AtomicBoolean();
        application.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> {
            if (opened.compareAndSet(false, true)) openBrowser(event.getApplicationContext(), browser);
        });
        return application.run(args);
    }

    private static void openBrowser(ConfigurableApplicationContext context, BrowserOpener browser) {
        String address = context.getEnvironment().getProperty("server.address", "127.0.0.1");
        if (address == null || address.isBlank() || "0.0.0.0".equals(address) || "::".equals(address)) {
            address = "127.0.0.1";
        }
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        if (address.contains(":") && !address.startsWith("[")) address = "[" + address + "]";
        String basePath = context.getEnvironment().getProperty("server.servlet.context-path", "");
        URI uri = URI.create("http://" + address + ":" + port + basePath + "/#/");
        log.info("Desktop UI ready: {}", uri);
        try {
            browser.open(uri);
        } catch (Exception exception) {
            log.warn("Unable to open the system browser; open {} manually ({})", uri, exception.toString());
        }
    }
}
