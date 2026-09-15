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
    private boolean loading;
    private boolean usable;
    private boolean detailed;
    private long lastProgress;
    private long refreshAt;
    private String notice = "";
    private String language = "";
    private GuiButton select;
    private GuiButton refresh;
    private GuiButton rename;
    private GuiButton abandon;
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
        buttonList.clear();
        select = new GuiButton(0, split() + 10, height - 32, Math.max(70, width - split() - 22), 20,
                I18n.format("mountcollection.gui.select"));
        refresh = new GuiButton(1, 12, height - 32, 95, 20, I18n.format("mountcollection.gui.refresh"));
        buttonList.add(select); buttonList.add(refresh);
        rename = new GuiButton(2, split() + 10, height - 57, Math.max(70, width - split() - 22), 20,
                I18n.format("mountcollection.gui.rename"));
        saveName = new GuiButton(3, width / 2 - 105, height / 2 + 22, 100, 20, I18n.format("mountcollection.gui.save_name"));
        cancelName = new GuiButton(4, width / 2 + 5, height / 2 + 22, 100, 20, I18n.format("gui.cancel"));
        String oldText = nameInput == null ? "" : nameInput.getText();
        nameInput = new net.minecraft.client.gui.GuiTextField(5, fontRenderer, width / 2 - 105, height / 2 - 4, 210, 20);
        nameInput.setMaxStringLength(128); nameInput.setText(oldText); nameInput.setFocused(editing != null);
        buttonList.add(rename); buttonList.add(saveName); buttonList.add(cancelName);
        abandon = new GuiButton(6, split() + 10, height - 82, Math.max(70, width - split() - 22), 20,
                I18n.format("mountcollection.gui.abandon"));
        confirmAbandon = new GuiButton(7, width / 2 - 105, height / 2 + 30, 100, 20,
                I18n.format("mountcollection.gui.confirm_abandon"));
        cancelAbandon = new GuiButton(8, width / 2 + 5, height / 2 + 30, 100, 20, I18n.format("gui.cancel"));
        buttonList.add(abandon); buttonList.add(confirmAbandon); buttonList.add(cancelAbandon);
        updateButtons();
        if (session == null) { request(); }
    }
    private int split() { return width * 55 / 100; }
    private int visibleRows() { return Math.max(1, (height - 84) / 28); }
    private void request() {
        assembly.clear(); session = UUID.randomUUID(); loading = true; usable = false;
        lastProgress = System.nanoTime(); notice = "";
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
                assembly.begin((CollectionHeader) message); loading = true;
            } else if (message instanceof CollectionPage) {
                assembly.accept((CollectionPage) message);
            } else {
                CollectionReply.Result result = ((CollectionReply) message).getResult();
                notice = I18n.format("mountcollection.gui.result." + result.name().toLowerCase(Locale.ROOT));
                if (result == CollectionReply.Result.RATE_LIMITED || result == CollectionReply.Result.UNAVAILABLE
                        || result == CollectionReply.Result.STALE) {
                    loading = false; assembly.clear(); refreshAt = System.nanoTime() + 2000000000L;
                }
            }
            if (assembly.isComplete()) {
                view = assembly.finish(); loading = false; usable = true;
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
            }
        } catch (IllegalArgumentException invalid) {
            abandonStream();
            notice = I18n.format("mountcollection.gui.invalid"); refreshAt = Long.MAX_VALUE;
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
        loading = false; usable = false; assembly.clear();
        if (session != null && mc.getConnection() != null) {
            network.sendCollectionIntent(new CollectionIntent(CollectionIntent.Action.CLOSE, session, 0, null));
        }
        // Invalidate the old nonce so queued responses cannot revive this view.
        session = UUID.randomUUID();
    }
    @Override public void updateScreen() {
        if (loading && System.nanoTime() - lastProgress > 10000000000L) {
            abandonStream(); notice = I18n.format("mountcollection.gui.timeout"); refreshAt = Long.MAX_VALUE;
        } else if (editing == null && !abandonment.isOpen() && !loading && System.nanoTime() >= refreshAt) { request(); }
        if (view != null && !language.equals(mc.getLanguageManager().getCurrentLanguage().getLanguageCode())) { sort(); }
        nameInput.updateCursorCounter();
        updateButtons();
    }
    private void updateButtons() {
        select.visible = refresh.visible = rename.visible = abandon.visible = editing == null && !abandonment.isOpen();
        confirmAbandon.visible = cancelAbandon.visible = abandonment.isOpen();
        saveName.visible = cancelName.visible = editing != null;
        select.enabled = usable && !loading && highlighted != null;
        CollectionView.Entry row = highlightedEntry();
        rename.enabled = select.enabled && row != null && (row.getState() == CollectionView.State.LIVING
                || row.getState() == CollectionView.State.RECOVERING || row.getState() == CollectionView.State.READY);
        abandon.enabled = select.enabled && row != null
                && (row.getState() == CollectionView.State.LIVING || row.getState() == CollectionView.State.BUSY);
        refresh.enabled = !loading;
    }
    @Override protected void actionPerformed(GuiButton button) {
        if (button.id == 8) { abandonment.cancel(); updateButtons(); return; }
        if (button.id == 7 && abandonment.isOpen()) {
            CollectionIntent confirmed = abandonment.confirm();
            loading = true; usable = false; assembly.clear(); lastProgress = System.nanoTime();
            network.sendCollectionIntent(confirmed);
            updateButtons(); return;
        }
        if (button.id == 6 && abandon.enabled) {
            abandonment.open(session, highlighted, view.getSelectionRevision(), names.get(highlighted));
            draggingPreview = false; notice = ""; updateButtons(); return;
        }
        if (button.id == 4) { editing = null; updateButtons(); return; }
        if (button.id == 3 && editing != null) {
            String input = nameInput.getText();
            try { input = com.mahghuuuls.mountcollection.collection.MountNaming.normalize(input); }
            catch (IllegalArgumentException invalid) { notice = I18n.format("mountcollection.gui.result.invalid_name"); return; }
            loading = true; usable = false; assembly.clear(); lastProgress = System.nanoTime();
            network.sendCollectionIntent(new CollectionIntent(CollectionIntent.Action.RENAME,
                    session, editingRevision, editing, input));
            editing = null; updateButtons(); return;
        }
        if (button.id == 2 && rename.enabled) {
            editing = highlighted; editingRevision = view.getSelectionRevision();
            nameInput.setText(highlightedEntry().getCustomName()); nameInput.setFocused(true);
            notice = ""; updateButtons(); return;
        }
        if (button.id == 1) { request(); }
        else if (button.id == 0 && select.enabled) {
            loading = true; assembly.clear(); lastProgress = System.nanoTime();
            network.sendCollectionIntent(new CollectionIntent(CollectionIntent.Action.SELECT,
                    session, view.getSelectionRevision(), highlighted));
        }
    }
    @Override protected void mouseClicked(int x, int y, int button) throws IOException {
        boolean wasAbandoning = abandonment.isOpen();
        boolean wasEditing = editing != null;
        super.mouseClicked(x, y, button);
        if (wasAbandoning || abandonment.isOpen()) { return; }
        if (wasEditing || editing != null) { nameInput.mouseClicked(x, y, button); return; }
        if (button == 0 && x >= split() + 10 && x < width - 12 && y >= 120 && y < height - 87) {
            draggingPreview = true; lastDragX = x;
        }
        if (button == 0 && x >= 12 && x < split() && y >= 42 && y < 42 + visibleRows() * 28) {
            int index = scroll + (y - 42) / 28;
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
        if (editing == null && !abandonment.isOpen() && wheel != 0) { scroll += wheel > 0 ? -3 : 3; clampScroll(); }
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
        drawCenteredString(fontRenderer, "Mount Collection", width / 2, 12, 0xffffff);
        String status = loading ? I18n.format("mountcollection.gui.loading", assembly.getReceived(), assembly.getTotal()) : notice;
        drawCenteredString(fontRenderer, fontRenderer.trimStringToWidth(status, width - 20), width / 2, 28, 0xdddddd);
        for (int i = 0; i < visibleRows() && scroll + i < rows.size(); i++) {
            CollectionView.Entry row = rows.get(scroll + i); int top = 42 + i * 28;
            boolean high = row.getId().equals(highlighted);
            drawRect(12, top, split(), top + 26, high ? 0xff6194c5 : 0xff444444);
            drawRect(13, top + 1, split() - 1, top + 25, high ? 0xff253b51 : 0xcc181818);
            boolean selected = view != null && row.getId().equals(view.getSelected());
            drawString(fontRenderer, fontRenderer.trimStringToWidth((selected ? "* " : "") + names.get(row.getId()), split() - 32),
                    18, top + 4, selected ? 0xffdd66 : 0xffffff);
            drawString(fontRenderer, fontRenderer.trimStringToWidth(I18n.format("mountcollection.gui.state."
                            + row.getState().name().toLowerCase(Locale.ROOT)), split() - 32),
                    18, top + 15, 0xbbbbbb);
        }
        if (rows.size() > visibleRows()) {
            int track = visibleRows() * 28;
            int thumb = Math.max(8, track * visibleRows() / rows.size());
            int top = 42 + (track - thumb) * scroll / Math.max(1, rows.size() - visibleRows());
            drawRect(split() - 4, top, split() - 1, top + thumb, 0xffbbbbbb);
        }
        if (view != null && rows.isEmpty()) { drawString(fontRenderer, I18n.format("mountcollection.gui.empty"), 16, 48, 0xffffff); }
        CollectionView.Entry row = highlightedEntry();
        String tooltip = null;
        if (row == null) { preview.clear(); }
        if (row != null) {
            int left = split() + 10;
            fontRenderer.drawSplitString(names.get(row.getId()), left, 46, width - left - 12, 0xffffff);
            fontRenderer.drawSplitString(I18n.format("mountcollection.gui.state." + row.getState().name().toLowerCase(Locale.ROOT)),
                    left, 70, width - left - 12, 0xdddddd);
            if (row.getState() == CollectionView.State.RECOVERING) {
                fontRenderer.drawSplitString(I18n.format("mountcollection.gui.recovery",
                                row.getRecoveryTicks() / 20 + (row.getRecoveryTicks() % 20 == 0 ? 0 : 1)),
                        left, 86, width - left - 12, 0xbbbbbb);
            }
            List<String> badges = new ArrayList<>();
            PlacementProfile profile = row.getCharacteristics().getPlacementProfile();
            if (profile == PlacementProfile.WATER) { badges.add("water"); }
            if (profile == PlacementProfile.LAVA) { badges.add("lava"); }
            if (row.getCharacteristics().hasTrait(MountTrait.FLYING)) { badges.add("flying"); }
            for (int i = 0; i < badges.size(); i++) {
                int bx = left + i * 15; int by = 105;
                GlStateManager.color(1, 1, 1, 1); GlStateManager.enableBlend();
                mc.getTextureManager().bindTexture(new ResourceLocation("mountcollection", "textures/gui/badge_" + badges.get(i) + ".png"));
                drawModalRectWithCustomSizedTexture(bx, by, 0, 0, 9, 9, 9, 9);
                if (x >= bx && x < bx + 9 && y >= by && y < by + 9) { tooltip = I18n.format("mountcollection.gui.badge." + badges.get(i)); }
            }
            if (editing == null && !abandonment.isOpen()) {
                net.minecraft.entity.EntityLivingBase model = preview.prepare(row.getId(), row.getPreview(), mc.world);
                if (!com.mahghuuuls.mountcollection.client.preview.PreviewRenderer.draw(preview, model,
                        left, 120, width - left - 12, height - 207) && height >= 231) {
                    if (detailed && row.getPreview() != null && reportedPreviewFailures.size() < 16
                            && reportedPreviewFailures.add(row.getId())) {
                        com.mahghuuuls.mountcollection.MountCollectionMod.LOGGER.info(
                                "[MountCollection][COLLECTION] event=preview_fallback mount={}", row.getId());
                    }
                    fontRenderer.drawSplitString(I18n.format("mountcollection.gui.preview_unavailable"), left, 122,
                            width - left - 12, 0x999999);
                }
            }
        }
        if (editing != null) {
            drawRect(0, 38, width, height, 0xdd101010);
            drawCenteredString(fontRenderer, I18n.format("mountcollection.gui.rename_hint"), width / 2, height / 2 - 24, 0xffffff);
            nameInput.drawTextBox();
        }
        if (abandonment.isOpen()) {
            drawRect(0, 38, width, height, 0xdd101010);
            fontRenderer.drawSplitString(I18n.format("mountcollection.gui.abandon_hint", abandonment.getName()),
                    width / 2 - 105, height / 2 - 38, 210, 0xffffff);
        }
        super.drawScreen(x, y, partial);
        if (editing == null && !abandonment.isOpen() && tooltip != null) { drawHoveringText(fontRenderer.listFormattedStringToWidth(tooltip, 180), x, y); }
    }
    @Override public boolean doesGuiPauseGame() { return false; }
    @Override public void onGuiClosed() {
        abandonment.cancel();
        preview.clear(); draggingPreview = false;
        reportedPreviewFailures.clear();
        assembly.clear();
        if (session != null && mc.getConnection() != null) {
            network.sendCollectionIntent(new CollectionIntent(CollectionIntent.Action.CLOSE, session, 0, null));
        }
    }
}
