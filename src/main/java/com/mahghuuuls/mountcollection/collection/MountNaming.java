package com.mahghuuuls.mountcollection.collection;

import java.util.Objects;

/** Validated naming state; null means no name has yet been observed or assigned. */
public final class MountNaming {
    public static final int MAX_CODE_POINTS = 32;
    private final String customName;
    private final long revision;
    private final boolean pending;

    public MountNaming(String customName, long revision, boolean pending) {
        if (revision < 0 || (customName == null && (revision != 0 || pending))) {
            throw new IllegalArgumentException("invalid naming state");
        }
        if (customName != null && !normalize(customName).equals(customName)) {
            throw new IllegalArgumentException("stored name must be normalized");
        }
        this.customName = customName; this.revision = revision; this.pending = pending;
    }
    public static MountNaming unobserved() { return new MountNaming(null, 0, false); }
    public String getCustomName() { return customName; }
    public long getRevision() { return revision; }
    public boolean isPending() { return pending; }
    public MountNaming renamed(String input) {
        if (revision == Long.MAX_VALUE) { throw new IllegalStateException("naming revision exhausted"); }
        return new MountNaming(normalize(input), revision + 1, true);
    }
    public MountNaming applied() { return new MountNaming(customName, revision, false); }

    public static String normalize(String input) {
        Objects.requireNonNull(input, "input");
        // Validate before trimming so leading/trailing controls cannot disappear.
        for (int offset = 0; offset < input.length();) {
            char first = input.charAt(offset);
            if (Character.isSurrogate(first) && (!Character.isHighSurrogate(first)
                    || offset + 1 == input.length() || !Character.isLowSurrogate(input.charAt(offset + 1)))) {
                throw new IllegalArgumentException("malformed Unicode name");
            }
            int point = input.codePointAt(offset);
            if (Character.isISOControl(point) || point == 0x00a7 || point == 0x2028 || point == 0x2029) {
                throw new IllegalArgumentException("unsafe name character");
            }
            offset += Character.charCount(point);
        }
        int start = 0, end = input.length();
        while (start < end && isSpace(input.codePointAt(start))) { start += Character.charCount(input.codePointAt(start)); }
        while (end > start && isSpace(input.codePointBefore(end))) { end -= Character.charCount(input.codePointBefore(end)); }
        String result = input.substring(start, end);
        if (result.codePointCount(0, result.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("name is longer than 32 Unicode code points");
        }
        return result;
    }
    private static boolean isSpace(int point) { return Character.isWhitespace(point) || Character.isSpaceChar(point); }
}
