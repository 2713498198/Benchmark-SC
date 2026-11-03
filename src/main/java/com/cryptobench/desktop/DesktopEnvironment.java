package com.cryptobench.desktop;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Runs after config-data loading and before Logback opens its output file. */
final class DesktopEnvironment implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    private final DesktopPaths paths;
    DesktopEnvironment(DesktopPaths paths) { this.paths = paths; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 15; }
    @Override public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) { normalize(event.getEnvironment(), paths); }

    static void normalize(ConfigurableEnvironment environment, DesktopPaths paths) {
        var safety = new HashMap<String, Object>();
        safety.put("server.address", "127.0.0.1");
        safety.put("benchmark.demo-enabled", false);
        safety.put("cryptobench.desktop.home", paths.home.toString().replace('\\', '/'));
        for (String property : List.of("benchmark.mail.key-file", "benchmark.mail.outbox", "benchmark.password-reset.outbox", "logging.file.name")) {
            String value = environment.getProperty(property);
            if (value != null && !value.isBlank()) safety.put(property, absolute(paths, value));
        }
        String database = environment.getProperty("spring.datasource.url", "");
        String prefix = "jdbc:h2:file:";
        if (database.startsWith(prefix)) {
            int settings = database.indexOf(';', prefix.length());
            String file = database.substring(prefix.length(), settings < 0 ? database.length() : settings);
            safety.put("spring.datasource.url", prefix + absolute(paths, file).replace('\\', '/') + (settings < 0 ? "" : database.substring(settings)));
        }
        environment.getPropertySources().addFirst(new MapPropertySource("desktopSafety", safety));
    }

    private static String absolute(DesktopPaths paths, String value) {
        Path file = Path.of(value);
        return (file.isAbsolute() ? file : paths.home.resolve(file)).normalize().toString();
    }
}
