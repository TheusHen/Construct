package org.theushen.construct.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class ConstructApiKeyScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget keyField;

    public ConstructApiKeyScreen(Screen parent) {
        super(Text.literal("Construct API Key"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = this.width;
        int h = this.height;

        String existing = ConstructKeyConfig.getKey();

        this.keyField = new TextFieldWidget(this.textRenderer, w / 2 - 140, h / 2 - 22, 280, 20, Text.literal(""));
        this.keyField.setMaxLength(256);
        this.keyField.setText(existing);
        this.addDrawableChild(this.keyField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save & Send"), btn -> {
            String key = keyField.getText().trim();
            ConstructClient.saveAndSend(key);
            this.close();
        }).dimensions(w / 2 - 140, h / 2 + 6, 136, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), btn -> {
            ConstructClient.saveAndSend("");
            this.close();
        }).dimensions(w / 2 + 4, h / 2 + 6, 68, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> {
            this.close();
        }).dimensions(w / 2 + 76, h / 2 + 6, 64, 20).build());

        this.setInitialFocus(this.keyField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Avoid applying blur twice in the same frame (can crash on some screen API paths).
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

        String key = ConstructKeyConfig.getKey();
        boolean set = key != null && !key.isBlank();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "Status: " + (set ? "set" : "unset"), this.width / 2 - 140, this.height / 2 - 45, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, "API Key:", this.width / 2 - 140, this.height / 2 - 34, 0xFFFFFF);

        String masked = mask(this.keyField.getText());
        context.drawTextWithShadow(this.textRenderer, masked, this.width / 2 - 136, this.height / 2 - 18, 0xAAAAAA);

        this.keyField.render(context, mouseX, mouseY, delta);
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
