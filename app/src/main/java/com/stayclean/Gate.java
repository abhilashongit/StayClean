package com.stayclean;

/** Single-flight gate: a burst of events (force-open spam) produces exactly one overlay cycle at a time. */
public final class Gate {
    private boolean busy;
    public synchronized boolean tryAcquire() { if (busy) return false; busy = true; return true; }
    public synchronized void release() { busy = false; }
    public synchronized boolean isBusy() { return busy; }
}
