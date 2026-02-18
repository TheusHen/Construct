package org.theushen.construct.utils;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class Progress_Bar {
    private static final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    public static void start(ServerCommandSource source, String title) {
        UUID id = getId(source);
        State s = new State(title);
        states.put(id, s);
        send(source, render(s), true);
    }

    public static void update(ServerCommandSource source, double progress01, String detail) {
        UUID id = getId(source);
        State s = states.get(id);
        if (s == null) {
            s = new State("Working");
            states.put(id, s);
        }

        s.progress01 = clamp01(progress01);
        if (detail != null) {
            s.detail = detail;
        }

        long now = System.currentTimeMillis();
        if (now - s.lastSendMs < 250) {
            return;
        }
        s.lastSendMs = now;

        send(source, render(s), true);
    }

    public static void finish(ServerCommandSource source, String message) {
        UUID id = getId(source);
        State s = states.get(id);

        String title = s != null ? s.title : "Working";
        if (message != null && !message.isBlank()) {
            title += " - " + message;
        }

        send(source, Text.literal(title), true);
        states.remove(id);
    }

    private static Text render(State s) {
        int pct = (int) Math.round(s.progress01 * 100.0);
        String bar = bar10(s.progress01);
        String tail = (s.detail != null && !s.detail.isBlank()) ? (" - " + s.detail) : "";
        return Text.literal(s.title + " " + bar + " " + pct + "%" + tail);
    }

    private static String bar10(double p) {
        int filled = (int) Math.round(p * 10.0);
        if (filled < 0) {
            filled = 0;
        }
        if (filled > 10) {
            filled = 10;
        }

        StringBuilder sb = new StringBuilder(12);
        sb.append("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? "#" : "-");
        }
        sb.append("]");
        return sb.toString();
    }

    private static void send(ServerCommandSource source, Text text, boolean actionBar) {
        source.sendFeedback(() -> text, actionBar);
    }

    private static UUID getId(ServerCommandSource source) {
        if (source.getEntity() != null) {
            return source.getEntity().getUuid();
        }
        return UUID.nameUUIDFromBytes(source.getName().getBytes());
    }

    private static double clamp01(double v) {
        if (v < 0) {
            return 0;
        }
        if (v > 1) {
            return 1;
        }
        return v;
    }

    private static final class State {
        String title;
        String detail = "";
        double progress01 = 0.0;
        long lastSendMs = 0;

        State(String title) {
            this.title = title;
        }
    }
}
