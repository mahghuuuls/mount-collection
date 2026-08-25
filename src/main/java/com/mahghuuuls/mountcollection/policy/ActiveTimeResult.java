package com.mahghuuuls.mountcollection.policy;

public final class ActiveTimeResult {

    public enum Status {
        VALID,
        NEGATIVE_INPUT,
        BACKWARD_INPUT,
        OVERFLOW_SATURATED
    }

    private final long value;
    private final Status status;

    ActiveTimeResult(long value, Status status) {
        this.value = value;
        this.status = status;
    }

    public long getValue() {
        return value;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isValid() {
        return status == Status.VALID;
    }
}
