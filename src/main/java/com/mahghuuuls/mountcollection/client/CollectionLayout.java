package com.mahghuuuls.mountcollection.client;

/** Shared scaled-screen geometry for drawing, clipping and hit testing. */
final class CollectionLayout {
    static final int BADGE_SIZE = 14;
    static final int STATUS_OFFSET = 60;
    static final int RECOVERY_OFFSET = 76;
    int badgeLeft(int index) { return detailLeft() + index * 20; }
    int badgeTop() { return top + 90; }
    boolean inBadge(int x, int y, int index) {
        return x >= badgeLeft(index) && x < badgeLeft(index) + BADGE_SIZE
                && y >= badgeTop() && y < badgeTop() + BADGE_SIZE;
    }
    final int left, top, width, height;
    CollectionLayout(int screenWidth, int screenHeight) {
        width = Math.max(1, Math.min(460, screenWidth - 16));
        height = Math.max(1, Math.min(300, screenHeight - 16));
        left = (screenWidth - width) / 2;
        top = (screenHeight - height) / 2;
    }
    int right() { return left + width; }
    int bottom() { return top + height; }
    int centerX() { return left + width / 2; }
    int centerY() { return top + height / 2; }
    int split() { return left + width / 2; }
    int listLeft() { return left + 10; }
    int listTop() { return top + 38; }
    int listWidth() { return split() - listLeft() - 6; }
    int visibleRows() { return Math.max(1, (height - 76) / 28); }
    int detailLeft() { return split() + 8; }
    int detailWidth() { return right() - detailLeft() - 10; }
    int renameTop() { return top + 34; }
    int nameLeft() { return detailLeft() + 24; }
    int nameWidth() { return detailWidth() - 24; }
    int abandonLeft() { return right() - 30; }
    int abandonTop() { return bottom() - 58; }
    boolean inName(int x, int y) {
        return x >= nameLeft() && x < nameLeft() + nameWidth()
                && y >= top + 38 && y < top + 48;
    }
    int previewTop() { return top + 106; }
    int previewHeight() { return Math.max(0, height - 170); }
    int dialogWidth() { return Math.max(1, Math.min(260, width - 24)); }
    int dialogLeft() { return centerX() - dialogWidth() / 2; }
    boolean inDetailLine(int x, int y, int offset) {
        return x >= detailLeft() && x < detailLeft() + detailWidth()
                && y >= top + offset && y < top + offset + 10;
    }
    boolean inPreview(int x, int y) {
        return x >= detailLeft() && x < detailLeft() + detailWidth()
                && y >= previewTop() && y < previewTop() + previewHeight();
    }
    boolean inList(int x, int y) {
        return x >= listLeft() && x < listLeft() + listWidth()
                && y >= listTop() && y < listTop() + visibleRows() * 28;
    }
}
