package com.cryptobench.desktop;

import com.cryptobench.benchmark.CryptoBenchApplication;
import java.awt.Desktop;
import java.net.URI;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Entry point used by the optional Windows app-image. The normal Spring Boot
 * entry point remains unchanged; this launcher only opens the local web UI
 * after the embedded server has become ready.
 */
public final class DesktopLauncher {
    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CryptoBenchApplication.class);
        application.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> openBrowser(event.getApplicationContext().getEnvironment()));
        application.run(args);
    }

    private static void openBrowser(Environment environment) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return;
        String address = environment.getProperty("server.address", "127.0.0.1");
        if (address == null || address.isBlank() || "0.0.0.0".equals(address) || "::".equals(address)) {
            address = "127.0.0.1";
        }
        int port = environment.getProperty("server.port", Integer.class, 4173);
        try {
            Desktop.getDesktop().browse(URI.create("http://" + address + ":" + port + "/#/"));
        } catch (Exception ignored) {
            // The server remains usable when the OS has no browser integration.
        }
    }
}
