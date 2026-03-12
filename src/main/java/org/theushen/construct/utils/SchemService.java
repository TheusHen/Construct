package org.theushen.construct.utils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class SchemService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Path EXAMPLE_SQUEM_PATH = resolveExampleSquemPath();

    private SchemService() {
    }

    public record PlaceResult(Path savedPath, int blocksPlaced) {
    }

    private static Path resolveExampleSquemPath() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (cwd.getFileName() != null && "run".equalsIgnoreCase(cwd.getFileName().toString())) {
            Path parent = cwd.getParent();
            if (parent != null) {
                return parent.resolve("examples").resolve("example.squem");
            }
        }
        return cwd.resolve("examples").resolve("example.squem");
    }

    public static Path ensureExampleSquemFile() throws IOException {
        Path parent = EXAMPLE_SQUEM_PATH.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(EXAMPLE_SQUEM_PATH) || Files.size(EXAMPLE_SQUEM_PATH) == 0) {
            byte[] bytes = createExampleSchemBytes();
            Files.write(EXAMPLE_SQUEM_PATH, bytes);
            LOGGER.info("SchemService created example squem: path='{}', bytes={}",
                    EXAMPLE_SQUEM_PATH.toAbsolutePath(), bytes.length);
        }
        return EXAMPLE_SQUEM_PATH;
    }

    public static Path exampleSquemPath() {
        return EXAMPLE_SQUEM_PATH;
    }

    public static PlaceResult placeExampleSquem(ServerCommandSource source, ServerPlayerEntity player) throws Exception {
        Path path = ensureExampleSquemFile();
        byte[] bytes = Files.readAllBytes(path);
        LOGGER.info("SchemService placeExampleSquem: path='{}', bytes={}",
                path.toAbsolutePath(), bytes.length);
        return saveAndPlace(source, player, "example_test", bytes);
    }

    public static PlaceResult saveAndPlace(ServerCommandSource source, ServerPlayerEntity player, String request, byte[] schemBytes) throws Exception {
        long totalStart = System.nanoTime();
        LOGGER.info("SchemService start: player='{}', request='{}', bytes={}",
                player.getName().getString(), oneLine(request), schemBytes == null ? 0 : schemBytes.length);

        long saveStart = System.nanoTime();
        Path saved = saveSchem(source, request, schemBytes);
        LOGGER.info("SchemService save done: path='{}', durationMs={}", saved.toAbsolutePath(), elapsedMs(saveStart));

        long placeStart = System.nanoTime();
        int placed = placeSpongeSchem(source, player, schemBytes);
        LOGGER.info("SchemService place done: blocksPlaced={}, durationMs={}", placed, elapsedMs(placeStart));
        LOGGER.info("SchemService complete: totalDurationMs={}", elapsedMs(totalStart));
        return new PlaceResult(saved, placed);
    }

    private static Path saveSchem(ServerCommandSource source, String request, byte[] schemBytes) throws IOException {
        Path root = source.getServer().getSavePath(WorldSavePath.ROOT)
                .resolve("construct_schematics");
        Files.createDirectories(root);

        String safeRequest = request == null ? "build" : request.toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (safeRequest.isBlank()) {
            safeRequest = "build";
        }

        String fileName = TS.format(LocalDateTime.now()) + "_" + safeRequest + ".schem";
        Path out = root.resolve(fileName);
        Files.write(out, schemBytes);
        return out;
    }

    private static byte[] createExampleSchemBytes() throws IOException {
        // Simple 3x3x3 solid stone cube for deterministic placement tests.
        int width = 3;
        int height = 3;
        int length = 3;

        NbtCompound root = new NbtCompound();
        root.putInt("Version", 2);
        root.putInt("DataVersion", 4325);
        root.putShort("Width", (short) width);
        root.putShort("Height", (short) height);
        root.putShort("Length", (short) length);
        root.putIntArray("Offset", new int[]{0, 0, 0});

        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone", 1);
        root.put("Palette", palette);
        root.putInt("PaletteMax", 2);

        byte[] blockData = new byte[width * height * length];
        for (int i = 0; i < blockData.length; i++) {
            blockData[i] = 1;
        }
        root.putByteArray("BlockData", blockData);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(root, out);
            return out.toByteArray();
        }
    }

    private static int placeSpongeSchem(ServerCommandSource source, ServerPlayerEntity player, byte[] schemBytes) throws Exception {
        NbtCompound root = readSchemRoot(schemBytes);
        int width = Short.toUnsignedInt(root.getShort("Width").orElse((short) 0));
        int height = Short.toUnsignedInt(root.getShort("Height").orElse((short) 0));
        int length = Short.toUnsignedInt(root.getShort("Length").orElse((short) 0));
        LOGGER.info("SchemService dimensions: width={}, height={}, length={}", width, height, length);

        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalStateException("Invalid schematic dimensions.");
        }

        NbtCompound paletteNbt = root.getCompound("Palette").orElse(new NbtCompound());
        byte[] blockData = root.getByteArray("BlockData").orElse(new byte[0]);

        Map<Integer, BlockState> paletteById = parsePalette(paletteNbt);
        int[] ids = decodeVarIntArray(blockData, width * height * length);
        LOGGER.info("SchemService decoded data: paletteSize={}, ids={}", paletteById.size(), ids.length);

        ServerWorld world = source.getWorld();
        BlockPos origin = frontOrigin(player, width);
        LOGGER.info("SchemService placement origin: x={}, y={}, z={}", origin.getX(), origin.getY(), origin.getZ());

        int placed = 0;
        for (int index = 0; index < ids.length; index++) {
            int id = ids[index];
            BlockState state = paletteById.get(id);
            if (state == null || state.isAir()) {
                continue;
            }

            int x = index % width;
            int yz = index / width;
            int z = yz % length;
            int y = yz / length;

            BlockPos pos = origin.add(x, y, z);
            world.setBlockState(pos, state, Block.NOTIFY_ALL);
            placed++;
        }
        return placed;
    }

    private static BlockPos frontOrigin(ServerPlayerEntity player, int width) {
        Direction forward = player.getHorizontalFacing();
        Direction right = forward.rotateYClockwise();
        BlockPos base = player.getBlockPos().offset(forward, 4);
        return base.offset(right, -(width / 2));
    }

    private static NbtCompound readSchemRoot(byte[] schemBytes) throws IOException {
        if (schemBytes == null || schemBytes.length < 2) {
            throw new IllegalStateException("Schematic payload is empty or too small.");
        }
        int b0 = schemBytes[0] & 0xFF;
        int b1 = schemBytes[1] & 0xFF;
        if (b0 != 0x1F || b1 != 0x8B) {
            throw new IllegalStateException("AI returned bytes that are not a gzipped .schem file (invalid header).");
        }

        try (ByteArrayInputStream in = new ByteArrayInputStream(schemBytes)) {
            try {
                return NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
            } catch (IOException e) {
                throw new IllegalStateException("Invalid .schem gzip/NBT payload: " + e.getMessage(), e);
            }
        }
    }

    private static Map<Integer, BlockState> parsePalette(NbtCompound paletteNbt) {
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String stateString : paletteNbt.getKeys()) {
            int id = paletteNbt.getInt(stateString).orElse(0);
            BlockState state = parseBlockState(stateString);
            palette.put(id, state);
        }
        return palette;
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static long elapsedMs(long startedAtNano) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNano);
    }

    private static BlockState parseBlockState(String input) {
        String idPart = input;
        String propsPart = null;

        int propsStart = input.indexOf('[');
        if (propsStart >= 0 && input.endsWith("]")) {
            idPart = input.substring(0, propsStart);
            propsPart = input.substring(propsStart + 1, input.length() - 1);
        }

        Identifier blockId = Identifier.of(idPart);
        Block block = Registries.BLOCK.get(blockId);
        BlockState state = block.getDefaultState();

        if (propsPart == null || propsPart.isBlank()) {
            return state;
        }

        String[] props = propsPart.split(",");
        for (String prop : props) {
            String[] kv = prop.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String name = kv[0].trim();
            String value = kv[1].trim();
            Property<?> property = block.getStateManager().getProperty(name);
            if (property == null) {
                continue;
            }
            state = applyPropertyValue(state, property, value);
        }

        return state;
    }

    private static <T extends Comparable<T>> BlockState applyPropertyValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.parse(value);
        return parsed.map(t -> state.with(property, t)).orElse(state);
    }

    private static int[] decodeVarIntArray(byte[] bytes, int expectedValues) {
        int[] out = new int[expectedValues];
        int outIndex = 0;
        int i = 0;

        while (i < bytes.length && outIndex < expectedValues) {
            int value = 0;
            int position = 0;
            while (true) {
                if (i >= bytes.length) {
                    throw new IllegalStateException("Unexpected end of BlockData.");
                }
                int b = bytes[i++] & 0xFF;
                value |= (b & 0x7F) << position;
                if ((b & 0x80) == 0) {
                    break;
                }
                position += 7;
                if (position > 35) {
                    throw new IllegalStateException("Invalid varint in BlockData.");
                }
            }
            out[outIndex++] = value;
        }

        if (outIndex != expectedValues) {
            throw new IllegalStateException("BlockData count mismatch. Expected " + expectedValues + ", got " + outIndex);
        }

        return out;
    }
}
