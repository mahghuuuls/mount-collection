package com.mahghuuuls.mountcollection.policy;

public interface ConfigWarningSink {

    void warn(String key, String rejectedValue, String fallback);
}
