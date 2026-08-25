package com.mahghuuuls.mountcollection.diagnostics;

import java.util.Map;

public interface DiagnosticSink {

    void detail(DiagnosticCategory category, String event, Map<String, String> fields);

    void essentialWarning(String category, String rejectedValue, String fallback);
}
