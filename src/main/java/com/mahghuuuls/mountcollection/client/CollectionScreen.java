package com.mahghuuuls.mountcollection.client;

import com.mahghuuuls.mountcollection.api.MountTrait;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.network.CollectionAssembly;
import com.mahghuuuls.mountcollection.network.CollectionHeader;
import com.mahghuuuls.mountcollection.network.CollectionIntent;
import com.mahghuuuls.mountcollection.network.CollectionPage;
import com.mahghuuuls.mountcollection.network.CollectionReply;
import com.mahghuuuls.mountcollection.network.MountNetwork;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.lwjgl.input.Mouse;

/** Highlight and sorting are local; confirmed management actions request server mutations. */
public final class CollectionScreen extends GuiScreen {
    private final MountNetwork network;
    private final CollectionAssembly assembly = new CollectionAssembly();
    private final List<CollectionView.Entry> rows = new ArrayList<>();
    private final java.util.Map<MountId, String> names = new java.util.HashMap<>();
    private final java.util.Map<MountId, CollectionView.Entry> entries = new java.util.HashMap<>();
    private CollectionView view;
    private UUID session;
    private MountId highlighted;
    private int scroll;
    private final CollectionRefresh transfer = new CollectionRefresh();
    private CollectionLayout layout;
    private boolean detailed;
    private long lastProgress;
    private long refreshAt;
    private String language = "";
    private GuiButton select;
    private GuiButton refresh;
    private CollectionIconButton rename;
    private CollectionIconButton abandon;
    private GuiButton confirmAbandon;
    private GuiButton cancelAbandon;
    private final AbandonmentConfirmation abandonment = new AbandonmentConfirmation();
    private net.minecraft.client.gui.GuiTextField nameInput;
    private GuiButton saveName;
    private GuiButton cancelName;
    private MountId editing;
    private long editingRevision;
    private final com.mahghuuuls.mountcollection.client.preview.PreviewSession preview;
    private boolean draggingPreview;
    private int lastDragX;
    private final java.util.Set<MountId> reportedPreviewFailures = new java.util.HashSet<>();

    public CollectionScreen(MountNetwork network, com.mahghuuuls.mountcollection.client.preview.ClientPreviewRegistry previews) {
        this.network = network;
        preview = new com.mahghuuuls.mountcollection.client.preview.PreviewSession(previews);
    }
    @Override public void initGui() {
        layout = new CollectionLayout(width, height);
        buttonList.clear();
        select = new GuiButton(0, layout.detailLeft(), layout.bottom() - 30, layout.detailWidth(), 20,
                I18n.format("mountcollection.gui.select"));
        refresh = new GuiButton(1, layout.listLeft(), layout.bottom() - 30, Math.min(95, layout.listWidth()), 20, I18n.format("mountcollection.gui.retry"));
        buttonList.add(select); buttonList.add(refresh);
        rename = new CollectionIconButton(2, layout.detailLeft(), layout.renameTop(),
                new ResourceLocation("minecraft", "textures/items/name_tag.png"), 16);
        int dialogButtonWidth = (layout.dialogWidth() - 8) / 2;
        saveName = new GuiButton(3, layout.dialogLeft(), layout.centerY() + 22, dialogButtonWidth, 20, I18n.format("mountcollection.gui.save_name"));
        cancelName = new GuiButton(4, layout.dialogLeft() + dialogButtonWidth + 8, layout.centerY() + 22, dialogButtonWidth, 20, I18n.format("gui.cancel"));
        String oldText = nameInput == null ? "" : nameInput.getText();
        nameInput = new net.minecraft.client.gui.GuiTextField(5, fontRenderer, layout.dialogLeft(), layout.centerY() - 4, layout.dialogWidth(), 20);
        nameInput.setMaxStringLength(128); nameInput.setText(oldText); nameInput.setFocused(editing != null);
        buttonList.add(rename); buttonList.add(saveName); buttonList.add(cancelName);
        abandon = new CollectionIconButton(6, layout.abandonLeft(), layout.abandonTop(), "action_abandon");
        confirmAbandon = new GuiButton(7, layout.dialogLeft(), layout.centerY() + 30, dialogButtonWidth, 20,
                I18n.format("mountcollection.gui.confirm_abandon"));
        cancelAbandon = new GuiButton(8, layout.dialogLeft() + dialogButtonWidth + 8, layout.centerY() + 30, dialogButtonWidth, 20, I18n.format("gui.cancel"));
        buttonList.add(abandon); buttonList.add(confirmAbandon); buttonList.add(cancelAbandon);
        updateButtons();
        clampScroll();
        if (session == null) { request(false); }
    }
    private int visibleRows() { return layout.visibleRows(); }
    private void request(boolean background) {
        assembly.clear(); session = UUID.randomUUID(); transfer.begin(background);
        lastProgress = System.nanoTime();
        network.sendCollectionIntent(new CollectionIntent(CollectionIntent.Action.OPEN, session, 0, null));
    }
    public void receive(IMessage message) {
        UUID incoming = message instanceof CollectionHeader ? ((CollectionHeader) message).getSnapshot()
                : message instanceof CollectionPage ? ((CollectionPage) message).getSnapshot()
                : ((CollectionReply) message).getSession();
        if (!incoming.equals(session)) { return; }
        lastProgress = System.nanoTime();
        try {
            if (message instanceof CollectionHeader) {
                detailed = ((CollectionHeader) message).isDetailed();
                assembly.begin((CollectionHeader) message);
            } else if (message instanceof CollectionPage) {
                assembly.accept((CollectionPage) message);
            } else {
                CollectionReply.Result result = ((CollectionReply) message).getResult();
                transfer.reply(result);
                if (result == CollectionReply.Result.RATE_LIMITED || result == CollectionReply.Result.UNAVAILABLE
                        || result == CollectionReply.Result.STALE) {
                    transfer.invalidate(); assembly.clear(); refreshAt = System.nanoTime() + 2000000000L;
                }
            }
            if (assembly.isComplete()) {
                view = assembly.finish();
                CollectionIntent deferred = transfer.complete();
                entries.clear();
                for (CollectionView.Entry entry : view.getEntries()) { entries.put(entry.getId(), entry); }
                if (detailed) {
                    com.mahghuuuls.mountcollection.MountCollectionMod.LOGGER.info(
                            "[MountCollection][COLLECTION] event=collection_assembled snapshot={} entries={} selection_revision={}",
                            session, view.getEntries().size(), view.getSelectionRevision());
                }
                rows.clear(); rows.addAll(view.getEntries()); sort();
                if (highlighted == null || rows.stream().noneMatch(row -> row.getId().equals(highlighted))) {
                    highlighted = view.getSelected() != null ? view.getSelected()
                            : rows.isEmpty() ? null : rows.get(0).getId();
                    scroll = 0;
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).getId().equals(highlighted)) { scroll = Math.max(0, i - visibleRows() + 1); break; }
                    }
                }
                clampScroll(); refreshAt = System.nanoTime() + 5000000000L;
                if (deferred != null) {
                    if (CollectionRefresh.stillCurrent(deferred, view)) { submit(deferred); }
                    else { transfer.reply(CollectionReply.Result.STALE); }
                }
            }
        } catch (IllegalArgumentException invalid) {
            abandonStream();
            transfer.notice("mountcollection.gui.invalid"); refreshAt = Long.MAX_VALUE;
        }
    }
    private String localizeName(CollectionView.Entry row) {
        if (!row.getCustomName().isEmpty()) { return row.getCustomName(); }
        String key = row.getTypeKey();
        String type = key;
        if (I18n.hasKey(key)) { type = I18n.format(key); }
        else {
            try {
                String vanilla = EntityList.getTranslationName(new ResourceLocation(key));
                if (vanilla != null) { type = I18n.format("entity." + vanilla + ".name"); }
            } catch (IllegalArgumentException ignored) { /* Provider key need not be a registry ID. */ }
        }
        return I18n.format("mountcollection.gui.fallback", type, row.getOrdinal());
    }
    private void sort() {
        names.clear();
        for (CollectionView.Entry row : rows) { names.put(row.getId(), localizeName(row)); }
        rows.sort(CollectionOrdering.byDisplayName(names));
        language = mc.getLanguageManager().getCurrentLanguage().getLanguageCode();
    }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows())); }
    private CollectionView.Entry highlightedEntry() {
        return entries.get(highlighted);
    }
    private void abandonStream() {
        transfer.invalidate(); assembly.clear();
        if (session != null && mc.getConnection() != null) {
            network.sendCollectionIntent(new CollectionIntent(CollectionIntent.Action.CLOSE, session, 0, null));
        }
        // Invalidate the old nonce so queued responses cannot revive this view.
        session = UUID.randomUUID();
    }
    @Override public void updateScreen() {
        if (transfer.pending() && System.nanoTime() - lastProgress > 10000000000L) {
            abandonStream(); transfer.notice("mountcollection.gui.timeout"); refreshAt = Long.MAX_VALUE;
        } else if (editing == null && !abandonment.isOpen() && !transfer.pending() && System.nanoTime() >= refreshAt) { request(true); }
        if (view != null && !language.equals(mc.getLanguageManager().getCurrentLanguage().getLanguageCode())) { sort(); }
        nameInput.updateCursorCounter();
        updateButtons();
    }
    private void updateButtons() {
        select.visible = refresh.visible = rename.visible = abandon.visible = editing == null && !abandonment.isOpen();
        confirmAbandon.visible = cancelAbandon.visible = abandonment.isOpen();
        saveName.visible = cancelName.visible = editing != null;
        saveName.enabled = confirmAbandon.enabled = transfer.actionable();
        select.enabled = transfer.actionable() && highlighted != null;
        CollectionView.Entry row = highlightedEntry();
        rename.enabled = select.enabled && row != null && (row.getState() == CollectionView.State.LIVING
                || row.getState() == CollectionView.State.RECOVERING || row.getState() == CollectionView.State.READY);
        abandon.enabled = select.enabled && row != null
                && (row.getState() == CollectionView.State.LIVING || row.getState() == CollectionView.State.BUSY);
        refresh.visible = editing == null && !abandonment.isOpen() && transfer.canRetry();
        refresh.enabled = refresh.visible;
    }
    private void submit(CollectionIntent intent) {
        if (transfer.submit(intent)) {
            assembly.clear(); lastProgress = System.nanoTime();
            network.sendCollectionIntent(intent);
        }
        updateButtons();
    }
    @Override protected void actionPerformed(GuiButton button) {
        if (button.id == 8) { abandonment.cancel(); updateButtons(); return; }
        if (button.id == 7 && abandonment.isOpen() && confirmAbandon.enabled) {
            CollectionIntent confirmed = abandonment.confirm();
            submit(confirmed);
            updateButtons(); return;
        }
        if (button.id == 6 && abandon.enabled) {
            abandonment.open(session, highlighted, view.getSelectionRevision(), names.get(highlighted));
            draggingPreview = false; transfer.notice(""); updateButtons(); return;
        }
        if (button.id == 4) { editing = null; updateButtons(); return; }
        if (button.id == 3 && editing != null && saveName.enabled) {
            String input = nameInput.getText();
            try { input = com.mahghuuuls.mountcollection.collection.MountNaming.normalize(input); }
            catch (IllegalArgumentException invalid) { transfer.notice("mountcollection.gui.result.invalid_name"); return; }
            submit(new CollectionIntent(CollectionIntent.Action.RENAME,
                    session, editingRevision, editing, input));
            editing = null; updateButtons(); return;
        }
        if (button.id == 2 && rename.enabled) {
            editing = highlighted; editingRevision = view.getSelectionRevision();
            nameInput.setText(highlightedEntry().getCustomName()); nameInput.setFocused(true);
            transfer.notice(""); updateButtons(); return;
        }
        if (button.id == 1 && transfer.canRetry()) {
            request(false);
        }
        else if (button.id == 0 && select.enabled) {
            submit(new CollectionIntent(CollectionIntent.Action.SELECT,
                    session, view.getSelectionRevision(), highlighted));
        }
    }
    @Override protected void mouseClicked(int x, int y, int button) throws IOException {
        boolean wasAbandoning = abandonment.isOpen();
        boolean wasEditing = editing != null;
        super.mouseClicked(x, y, button);
        if (wasAbandoning || abandonment.isOpen()) { return; }
        if (wasEditing || editing != null) { nameInput.mouseClicked(x, y, button); return; }
        if (button == 0 && layout.inPreview(x, y)) {
            draggingPreview = true; lastDragX = x;
        }
        if (button == 0 && layout.inList(x, y)) {
            int index = scroll + (y - layout.listTop()) / 28;
            if (index < rows.size()) { highlighted = rows.get(index).getId(); }
        }
    }
    @Override protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (editing == null && !abandonment.isOpen() && draggingPreview && button == 0) {
            preview.rotate((x - lastDragX) * 2F); lastDragX = x;
        }
    }
    @Override protected void mouseReleased(int x, int y, int button) {
        draggingPreview = false; super.mouseReleased(x, y, button);
    }
    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (editing == null && !abandonment.isOpen() && wheel != 0 && layout.inList(x, y)) {
            scroll += wheel > 0 ? -3 : 3; clampScroll();
        }
    }
    @Override protected void keyTyped(char character, int key) throws IOException {
        if (abandonment.isOpen()) {
            if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE) { abandonment.cancel(); updateButtons(); }
            return;
        }
        if (editing != null) {
            if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE) { editing = null; updateButtons(); }
            else if (key == org.lwjgl.input.Keyboard.KEY_RETURN || key == org.lwjgl.input.Keyboard.KEY_NUMPADENTER) { actionPerformed(saveName); }
            else { nameInput.textboxKeyTyped(character, key); }
            return;
        }
        super.keyTyped(character, key);
    }
    @Override public void drawScreen(int x, int y, float partial) {
        drawDefaultBackground();
        drawRect(layout.left, layout.top, layout.right(), layout.bottom(), 0xff777777);
        drawRect(layout.left + 1, layout.top + 1, layout.right() - 1, layout.bottom() - 1, 0xff202020);
        drawCenteredString(fontRenderer, "Mount Collection", layout.centerX(), layout.top + 9, 0xffffff);
        String status = transfer.showLoading() ? I18n.format("mountcollection.gui.loading", assembly.getReceived(), assembly.getTotal())
                : transfer.notice().isEmpty() ? "" : I18n.format(transfer.notice());
        drawCenteredString(fontRenderer, fontRenderer.trimStringToWidth(status, layout.width - 20), layout.centerX(), layout.top + 24, 0xdddddd);
        for (int i = 0; i < visibleRows() && scroll + i < rows.size(); i++) {
            CollectionView.Entry row = rows.get(scroll + i); int top = layout.listTop() + i * 28;
            boolean high = row.getId().equals(highlighted);
            int listLeft = layout.listLeft(), listRight = listLeft + layout.listWidth();
            drawRect(listLeft, top, listRight, top + 26, high ? 0xff6194c5 : 0xff444444);
            drawRect(listLeft + 1, top + 1, listRight - 1, top + 25, high ? 0xff253b51 : 0xcc181818);
            boolean selected = view != null && row.getId().equals(view.getSelected());
            drawString(fontRenderer, fontRenderer.trimStringToWidth((selected ? "* " : "") + names.get(row.getId()), layout.listWidth() - 16),
                    listLeft + 6, top + 4, selected ? 0xffdd66 : 0xffffff);
            drawString(fontRenderer, fontRenderer.trimStringToWidth(I18n.format("mountcollection.gui.state."
                            + row.getState().name().toLowerCase(Locale.ROOT)), layout.listWidth() - 16),
                    listLeft + 6, top + 15, 0xbbbbbb);
        }
        if (rows.size() > visibleRows()) {
            int track = visibleRows() * 28;
            int thumb = Math.max(8, track * visibleRows() / rows.size());
            int top = layout.listTop() + (track - thumb) * scroll / Math.max(1, rows.size() - visibleRows());
            int right = layout.listLeft() + layout.listWidth();
            drawRect(right - 4, top, right - 1, top + thumb, 0xffbbbbbb);
        }
        if (view != null && rows.isEmpty()) {
            fontRenderer.drawSplitString(I18n.format("mountcollection.gui.empty"), layout.listLeft() + 4,
                    layout.listTop() + 4, layout.listWidth() - 8, 0xffffff);
        }
        CollectionView.Entry row = highlightedEntry();
        String tooltip = null;
        if (row == null) { preview.clear(); }
        if (row != null) {
            int left = layout.detailLeft();
            drawString(fontRenderer, fontRenderer.trimStringToWidth(names.get(row.getId()), layout.nameWidth()),
                    layout.nameLeft(), layout.top + 38, 0xffffff);
            String stateText = I18n.format("mountcollection.gui.state." + row.getState().name().toLowerCase(Locale.ROOT));
            drawString(fontRenderer, fontRenderer.trimStringToWidth(stateText, layout.detailWidth()),
                    left, layout.top + CollectionLayout.STATUS_OFFSET, 0xdddddd);
            if (layout.inName(x, y)) {
                tooltip = names.get(row.getId());
            }
            if (layout.inDetailLine(x, y, CollectionLayout.STATUS_OFFSET)) { tooltip = stateText; }
            if (row.getState() == CollectionView.State.RECOVERING) {
                String recoveryText = I18n.format("mountcollection.gui.recovery",
                        row.getRecoveryTicks() / 20 + (row.getRecoveryTicks() % 20 == 0 ? 0 : 1));
                drawString(fontRenderer, fontRenderer.trimStringToWidth(recoveryText, layout.detailWidth()),
                        left, layout.top + CollectionLayout.RECOVERY_OFFSET, 0xbbbbbb);
                if (layout.inDetailLine(x, y, CollectionLayout.RECOVERY_OFFSET)) { tooltip = recoveryText; }
            }
            List<String> badges = new ArrayList<>();
            PlacementProfile profile = row.getCharacteristics().getPlacementProfile();
            if (profile == PlacementProfile.WATER) { badges.add("water"); }
            if (profile == PlacementProfile.LAVA) { badges.add("lava"); }
            if (row.getCharacteristics().hasTrait(MountTrait.FLYING)) { badges.add("flying"); }
            for (int i = 0; i < badges.size(); i++) {
                int bx = layout.badgeLeft(i); int by = layout.badgeTop();
                GlStateManager.color(1, 1, 1, 1); GlStateManager.enableBlend();
                mc.getTextureManager().bindTexture(new ResourceLocation("mountcollection", "textures/gui/badge_" + badges.get(i) + ".png"));
                drawScaledCustomSizeModalRect(bx, by, 0, 0, 9, 9,
                        CollectionLayout.BADGE_SIZE, CollectionLayout.BADGE_SIZE, 9, 9);
                if (layout.inBadge(x, y, i)) {
                    tooltip = I18n.format("mountcollection.gui.badge." + badges.get(i)
                            + ("flying".equals(badges.get(i)) && row.isSummoningDisabled() ? "_disabled" : ""));
                }
            }
            if (editing == null && !abandonment.isOpen()) {
                net.minecraft.entity.EntityLivingBase model = preview.prepare(row.getId(), row.getPreview(), mc.world);
                if (!com.mahghuuuls.mountcollection.client.preview.PreviewRenderer.draw(preview, model,
                        left, layout.previewTop(), layout.detailWidth(), layout.previewHeight()) && layout.previewHeight() >= 24) {
                    if (detailed && row.getPreview() != null && reportedPreviewFailures.size() < 16
                            && reportedPreviewFailures.add(row.getId())) {
                        com.mahghuuuls.mountcollection.MountCollectionMod.LOGGER.info(
                                "[MountCollection][COLLECTION] event=preview_fallback mount={}", row.getId());
                    }
                    fontRenderer.drawSplitString(I18n.format("mountcollection.gui.preview_unavailable"), left, layout.previewTop() + 2,
                            layout.detailWidth(), 0x999999);
                }
            }
        }
        if (editing != null) {
            drawRect(layout.left + 1, layout.top + 34, layout.right() - 1, layout.bottom() - 1, 0xee101010);
            fontRenderer.drawSplitString(I18n.format("mountcollection.gui.rename_hint"), layout.dialogLeft(),
                    layout.centerY() - 30, layout.dialogWidth(), 0xffffff);
            nameInput.drawTextBox();
        }
        if (abandonment.isOpen()) {
            drawRect(layout.left + 1, layout.top + 34, layout.right() - 1, layout.bottom() - 1, 0xee101010);
            fontRenderer.drawSplitString(I18n.format("mountcollection.gui.abandon_hint", abandonment.getName()),
                    layout.dialogLeft(), layout.centerY() - 38, layout.dialogWidth(), 0xffffff);
        }
        super.drawScreen(x, y, partial);
        if (rename.contains(x, y)) { tooltip = I18n.format("mountcollection.gui.rename"); }
        if (abandon.contains(x, y)) { tooltip = I18n.format("mountcollection.gui.abandon_tooltip"); }
        if (editing == null && !abandonment.isOpen() && tooltip != null) { drawHoveringText(fontRenderer.listFormattedStringToWidth(tooltip, 180), x, y); }
    }
    @Override public boolean doesGuiPauseGame() { return false; }
    @Override public void onGuiClosed() {
        abandonment.cancel();
        preview.clear(); draggingPreview = false;
        reportedPreviewFailures.clear();
        transfer.invalidate();
        assembly.clear();
        if (session != null && mc.getConnection() != null) {
            network.sendCollectionIntent(new CollectionIntent(CollectionIntent.Action.CLOSE, session, 0, null));
        }
    }
}
