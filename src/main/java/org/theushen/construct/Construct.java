package org.theushen.construct;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Construct implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Construct.class);

    // Toggle test-only command registration. True = enable /construct-test, False = production mode.
    public static final boolean ENABLE_TEST_COMMAND = false;

    @Override
    public void onInitialize() {
        try {
            java.nio.file.Path path = org.theushen.construct.utils.SchemService.ensureExampleSquemFile();
            LOGGER.info("Construct example schem ready: path='{}'", path.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("Construct failed to prepare example schem file: {}", e.getMessage());
        }
        org.theushen.construct.commands.Construct.register();
        org.theushen.construct.commands.History.register();
    }
}
