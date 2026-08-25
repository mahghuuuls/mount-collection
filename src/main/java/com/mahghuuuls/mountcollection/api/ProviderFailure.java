package com.mahghuuuls.mountcollection.api;

/**
 * Bounded provider failure categories safe for core policy and diagnostics.
 */
public enum ProviderFailure {
    UNSUPPORTED,
    NOT_TAMED,
    NOT_OWNED,
    INVALID_STATE,
    INVALID_PAYLOAD,
    INTERNAL_ERROR
}
