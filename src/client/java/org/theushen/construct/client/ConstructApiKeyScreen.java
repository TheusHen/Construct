package org.theushen.construct.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class ConstructApiKeyScreen extends Screen {

    private final Screen parent;
    private final String initialFocusTarget;
    private TextFieldWidget hackClubField;
    private TextFieldWidget geminiField;

    public ConstructApiKeyScreen(Screen parent) {
        this(parent, "hackclub");
    }

    public ConstructApiKeyScreen(Screen parent, String initialFocusTarget) {
        super(Text.literal("Construct AI Keys"));
        this.parent = parent;
        this.initialFocusTarget = initialFocusTarget == null ? "hackclub" : initialFocusTarget;
    }

    @Override
    protected void init() {
        int w = this.width;
        int h = this.height;

        this.hackClubField = new TextFieldWidget(this.textRenderer, w / 2 - 140, h / 2 - 28, 280, 20, Text.literal(""));
        this.hackClubField.setMaxLength(256);
        this.hackClubField.setText(ConstructKeyConfig.getHackClubKey());
        this.addDrawableChild(this.hackClubField);

        this.geminiField = new TextFieldWidget(this.textRenderer, w / 2 - 140, h / 2 + 14, 280, 20, Text.literal(""));
        this.geminiField.setMaxLength(256);
        this.geminiField.setText(ConstructKeyConfig.getGeminiKey());
        this.addDrawableChild(this.geminiField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save & Send"), btn -> {
            ConstructClient.saveAndSend(hackClubField.getText().trim(), geminiField.getText().trim());
            this.close();
        }).dimensions(w / 2 - 140, h / 2 + 48, 136, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear All"), btn -> {
            ConstructClient.saveAndSend("", "");
            this.close();
        }).dimensions(w / 2 + 4, h / 2 + 48, 68, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> {
            this.close();
        }).dimensions(w / 2 + 76, h / 2 + 48, 64, 20).build());

        this.setInitialFocus("gemini".equalsIgnoreCase(initialFocusTarget) ? this.geminiField : this.hackClubField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Avoid applying blur twice in the same frame (can crash on some screen API paths).
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

        boolean hackClubSet = !ConstructKeyConfig.getHackClubKey().isBlank();
        boolean geminiSet = !ConstructKeyConfig.getGeminiKey().isBlank();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 68, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "Hack Club AI: " + (hackClubSet ? "set" : "unset"), this.width / 2 - 140, this.height / 2 - 50, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "Primary provider. Used first when present.", this.width / 2 - 140, this.height / 2 - 40, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, "Hack Club API Key:", this.width / 2 - 140, this.height / 2 - 30, 0xFFFFFF);

        context.drawTextWithShadow(this.textRenderer, "Gemini: " + (geminiSet ? "set" : "unset"), this.width / 2 - 140, this.height / 2 - 8, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "Fallback provider when Hack Club fails or is unset.", this.width / 2 - 140, this.height / 2 + 2, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, "Gemini API Key:", this.width / 2 - 140, this.height / 2 + 12, 0xFFFFFF);

        context.drawTextWithShadow(this.textRenderer, mask(this.hackClubField.getText()), this.width / 2 - 136, this.height / 2 - 16, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, mask(this.geminiField.getText()), this.width / 2 - 136, this.height / 2 + 26, 0xAAAAAA);

        this.hackClubField.render(context, mouseX, mouseY, delta);
        this.geminiField.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return "*".repeat(Math.min(s.length(), 80));
    }
}
