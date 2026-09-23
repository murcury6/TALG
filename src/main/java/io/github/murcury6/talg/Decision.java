package io.github.murcury6.talg;

/** A research candidate, never an executable order. */
public record Decision(String status, long shares, double maxNotionalUsd, String reason) {
    public static Decision reject(String reason) {
        return new Decision("NO_TRADE", 0, 0.0, reason);
    }
}
