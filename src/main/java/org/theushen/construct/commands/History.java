package org.theushen.construct.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class History {
    public enum Status {
        QUEUED,
        CALLING_AI,
        DONE,
        ERROR
    }

    public static final class Entry {
        public final UUID owner;
        public final long startedAtMs;
        public volatile long finishedAtMs;
        public final String request;
        public volatile Status status;
        public volatile Integer schemBytes;
        public volatile String error;

        private Entry(UUID owner, String request) {
            this.owner = owner;
            this.request = request;
            this.startedAtMs = System.currentTimeMillis();
            this.status = Status.QUEUED;
        }
    }

    private static final int MAX_PER_USER = 25;
    private static final ConcurrentHashMap<UUID, Deque<Entry>> STORE = new ConcurrentHashMap<>();
    private static boolean registered = false;

    private History() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("history")
                        .executes(context -> showHistory(context.getSource(), 5))
                        .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, MAX_PER_USER))
                                .executes(context -> showHistory(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit"))))));
    }

    public static Entry start(ServerCommandSource source, String request) {
        UUID owner = getId(source);
        Entry e = new Entry(owner, request);

        Deque<Entry> dq = STORE.computeIfAbsent(owner, k -> new ConcurrentLinkedDeque<>());
        dq.addFirst(e);
        trim(dq);

        return e;
    }

    public static void updateStatus(Entry entry, Status status, String note) {
        entry.status = status;
        if (note != null && !note.isBlank()) {
            entry.error = note;
        }
    }

    public static void finishSuccess(Entry entry, int schemBytes) {
        entry.status = Status.DONE;
        entry.schemBytes = schemBytes;
        entry.finishedAtMs = System.currentTimeMillis();
    }

    public static void finishError(Entry entry, String error) {
        entry.status = Status.ERROR;
        entry.error = error;
        entry.finishedAtMs = System.currentTimeMillis();
    }

    public static List<Entry> list(ServerCommandSource source) {
        UUID owner = getId(source);
        Deque<Entry> dq = STORE.get(owner);
        if (dq == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(dq);
    }

    private static int showHistory(ServerCommandSource source, int limit) {
        List<Entry> entries = list(source);
        if (entries.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No history yet."), false);
            return 1;
        }

        int shown = Math.min(limit, entries.size());
        source.sendFeedback(() -> Text.literal("Recent construct jobs: " + shown), false);

        for (int i = 0; i < shown; i++) {
            Entry entry = entries.get(i);
            String suffix;
            if (entry.status == Status.DONE) {
                suffix = "bytes=" + (entry.schemBytes == null ? 0 : entry.schemBytes);
            } else if (entry.status == Status.ERROR) {
                suffix = "error=" + (entry.error == null ? "unknown" : entry.error);
            } else {
                suffix = "in-progress";
            }

            String line = "#" + (i + 1) + " [" + entry.status + "] " + entry.request + " (" + suffix + ")";
            source.sendFeedback(() -> Text.literal(line), false);
        }

        return shown;
    }

    private static void trim(Deque<Entry> dq) {
        while (dq.size() > MAX_PER_USER) {
            dq.removeLast();
        }
    }

    private static UUID getId(ServerCommandSource source) {
        if (source.getEntity() != null) {
            return source.getEntity().getUuid();
        }
        return UUID.nameUUIDFromBytes(source.getName().getBytes());
    }
}