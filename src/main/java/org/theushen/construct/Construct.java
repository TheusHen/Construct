package org.theushen.construct;

import net.fabricmc.api.ModInitializer;

public class Construct implements ModInitializer {
    @Override
    public void onInitialize() {
        org.theushen.construct.commands.Construct.register();
        org.theushen.construct.commands.History.register();
    }
}
