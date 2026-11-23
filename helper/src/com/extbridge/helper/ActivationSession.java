package com.extbridge.helper;

import java.util.UUID;

/** One owned activation at a time. Cancelling invalidates commands and queued callbacks. */
public final class ActivationSession {
    public enum Mode { ROOT, AUTO_ROOT, PAIR, CONNECT, NOTIFICATION }
    private long generation;
    private Ticket active;

    public final class Ticket {
        public final String token = UUID.randomUUID().toString().replace("-", "");
        public final Mode mode;
        private final long epoch;
        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile Runnable cancellation;
        private volatile Runnable processCancellation;

        private Ticket(Mode mode, long epoch) { this.mode = mode; this.epoch = epoch; }
        public boolean isCancelled() { return cancelled; }
        public boolean isFinished() { return finished; }
        public boolean mayDeliver() { synchronized (ActivationSession.this) { return !cancelled && generation == epoch; } }
        public boolean sameGeneration() { synchronized (ActivationSession.this) { return generation == epoch; } }
        public synchronized void onCancel(Runnable hook) { cancellation = hook; if (cancelled) safely(hook); }
        public synchronized void attachProcess(Runnable hook) { processCancellation = hook; if (cancelled) safely(hook); }
        public synchronized void detachProcess() { processCancellation = null; }
        public void cancel() {
            Runnable hook, process;
            synchronized (this) {
                if (cancelled) return;
                // A completed service must stay running, but its queued UI/notification
                // result is still invalidated when the user closes or cancels it.
                if (finished) { cancelled = true; return; }
                cancelled = true; hook = cancellation; process = processCancellation;
            }
            safely(hook); safely(process);
        }
        public void finish() {
            synchronized (this) {
                finished = true;
            }
        }
    }

    public synchronized Ticket begin(Mode mode) {
        if (active != null && !active.isFinished() && !active.isCancelled()) return null;
        active = new Ticket(mode, ++generation);
        return active;
    }
    private static void safely(Runnable action) { if (action != null) try { action.run(); } catch (RuntimeException ignored) {} }
    public synchronized Ticket current() { return active; }
    public void cancelAll() { Ticket ticket; synchronized (this) { ticket = active; } if (ticket != null) ticket.cancel(); }
    public void cancelAutomatic() { Ticket ticket; synchronized (this) { ticket = active; } if (ticket != null && ticket.mode == Mode.AUTO_ROOT) ticket.cancel(); }
    public void cancelNotification() { Ticket ticket; synchronized (this) { ticket = active; } if (ticket != null && ticket.mode == Mode.NOTIFICATION) ticket.cancel(); }
}
