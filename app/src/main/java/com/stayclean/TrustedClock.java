package com.stayclean;

/**
 * Defends against the "change the phone clock" bypass.
 * A network-verified anchor (wall time + monotonic elapsedRealtime + boot id) lets us compute real time
 * even if the system clock is moved. Blocking uses OR: if EITHER clock says "night", we block.
 * Tampering can therefore only make the block stricter, never looser.
 */
public final class TrustedClock {
    public interface Store { long get(String key, long def); void put(String key, long value); }

    private static final String K_WALL = "a_wall", K_ELAPSED = "a_elapsed", K_BOOT = "a_boot";
    private final Store store;

    public TrustedClock(Store store) { this.store = store; }

    public void setAnchor(long wallMs, long elapsedNow, long bootId) {
        store.put(K_WALL, wallMs);
        store.put(K_ELAPSED, elapsedNow);
        store.put(K_BOOT, bootId);
    }

    /** Real time estimate, or -1 if there is no anchor for this boot (needs a fresh network sync). */
    public long trustedNow(long elapsedNow, long bootId) {
        long wall = store.get(K_WALL, -1);
        if (wall < 0) return -1;
        if (store.get(K_BOOT, Long.MIN_VALUE) != bootId) return -1;       // rebooted: elapsed clock restarted
        long elapsedAtAnchor = store.get(K_ELAPSED, -1);
        if (elapsedNow < elapsedAtAnchor) return -1;                        // monotonic clock went backwards: distrust
        return wall + (elapsedNow - elapsedAtAnchor);
    }

    public boolean blockedNow(long systemNow, long elapsedNow, long bootId) {
        if (Schedule.inWindow(systemNow)) return true;
        long t = trustedNow(elapsedNow, bootId);
        return t >= 0 && Schedule.inWindow(t);
    }

    public boolean tamperLockNow(long systemNow, long elapsedNow, long bootId) {
        if (Schedule.tamperLock(systemNow)) return true;
        long t = trustedNow(elapsedNow, bootId);
        return t >= 0 && Schedule.tamperLock(t);
    }

    /** The stricter of the system clock's phase and the network-anchored phase. */
    public Schedule.Phase phaseNow(long systemNow, long elapsedNow, long bootId) {
        Schedule.Phase a = Schedule.phase(systemNow);
        long t = trustedNow(elapsedNow, bootId);
        if (t >= 0) {
            Schedule.Phase b = Schedule.phase(t);
            if (b.ordinal() > a.ordinal()) a = b;
        }
        return a;
    }

    public boolean socialWindowNow(long systemNow, long elapsedNow, long bootId) {
        if (Schedule.socialWindow(systemNow)) return true;
        long t = trustedNow(elapsedNow, bootId);
        return t >= 0 && Schedule.socialWindow(t);
    }
}
