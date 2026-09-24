package com.mahghuuuls.mountcollection.lifecycle;

/** Transient safety decision, never a durable mount property or a boarding permission. */
public enum ArrivalDisposition {
    MOUNT_ONLY,
    COMBINED,
    UNMOUNTED_FALLBACK
}
