package com.mahghuuuls.mountcollection.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A provider result with no player-facing or implementation-detail text.
 */
public final class ProviderResult<T> {

    private final T value;
    private final ProviderFailure failure;

    private ProviderResult(T value, ProviderFailure failure) {
        this.value = value;
        this.failure = failure;
    }

    public static <T> ProviderResult<T> success(T value) {
        return new ProviderResult<>(Objects.requireNonNull(value, "value"), null);
    }

    public static ProviderResult<Void> success() {
        return new ProviderResult<>(null, null);
    }

    public static <T> ProviderResult<T> failure(ProviderFailure failure) {
        return new ProviderResult<>(null, Objects.requireNonNull(failure, "failure"));
    }

    public boolean isSuccess() {
        return failure == null;
    }

    public Optional<T> getValue() {
        return Optional.ofNullable(value);
    }

    public Optional<ProviderFailure> getFailure() {
        return Optional.ofNullable(failure);
    }
}
