package com.cryptobench.desktop;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;
import java.util.regex.Pattern;

/** Applies to both formatted messages and stack traces in the desktop log. */
public final class DesktopRedactionConverter extends CompositeConverter<ILoggingEvent> {
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)((?:password|passwd|token|secret|api[-_]?key|authorization)[\\w.-]*[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(authorization[\\\"']?\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s,;}]+");
    public static String redact(String text) {
        String result = text == null ? "" : text;
        for (var entry : System.getenv().entrySet()) {
            if (entry.getKey().matches("(?i).*(PASSWORD|TOKEN|SECRET|API_KEY|MAIL_KEY)$") && entry.getValue().length() >= 4) {
                result = result.replace(entry.getValue(), "[REDACTED]");
            }
        }
        result = AUTHORIZATION.matcher(result).replaceAll("$1[REDACTED]");
        return ASSIGNMENT.matcher(result).replaceAll("$1[REDACTED]");
    }
    @Override protected String transform(ILoggingEvent event, String input) { return redact(input); }
}
