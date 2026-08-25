package com.mahghuuuls.mountcollection.diagnostics;

import com.mahghuuuls.mountcollection.policy.ConfigWarningSink;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.apache.logging.log4j.Logger;

public final class MountCollectionDiagnostics implements DiagnosticSink, ConfigWarningSink {

    private static final int MAX_FIELDS = 12;
    private static final int MAX_KEY_LENGTH = 48;
    private static final int MAX_VALUE_LENGTH = 160;

    private final Logger logger;
    private volatile boolean detailedEnabled;

    public MountCollectionDiagnostics(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void setDetailedEnabled(boolean detailedEnabled) {
        this.detailedEnabled = detailedEnabled;
    }

    public boolean isDetailedEnabled() {
        return detailedEnabled;
    }

    @Override
    public void detail(DiagnosticCategory category, String event, Map<String, String> fields) {
        if (!detailedEnabled) {
            return;
        }
        StringJoiner joined = new StringJoiner(" ");
        int count = 0;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (count++ >= MAX_FIELDS) {
                joined.add("truncated=true");
                break;
            }
            joined.add(sanitize(field.getKey(), MAX_KEY_LENGTH)
                    + "="
                    + sanitize(field.getValue(), MAX_VALUE_LENGTH));
        }
        logger.info("[MountCollection][{}] event={} {}", category, sanitize(event, MAX_KEY_LENGTH), joined);
    }

    @Override
    public void essentialWarning(String category, String rejectedValue, String fallback) {
        logger.warn(
                "Mount Collection configuration rejected {}={} and used {}",
                sanitize(category, MAX_KEY_LENGTH),
                sanitize(rejectedValue, MAX_VALUE_LENGTH),
                sanitize(fallback, MAX_VALUE_LENGTH));
    }

    @Override
    public void warn(String key, String rejectedValue, String fallback) {
        essentialWarning(key, rejectedValue, fallback);
    }

    private static String sanitize(String value, int maximumLength) {
        String safe = String.valueOf(value)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ');
        if (safe.length() <= maximumLength) {
            return safe;
        }
        return safe.substring(0, maximumLength) + "...";
    }
}
