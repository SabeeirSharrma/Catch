package com.sabeeir.catchapp.shell;

/**
 * Service interface of the Shizuku user service.
 *
 * The implementation runs in a separate process owned by the Shizuku server, i.e. with
 * the identity of shell (uid 2000) or root (uid 0), and it has no hidden-API
 * restrictions. That is what lets Catch inject input with a display id attached without
 * touching the real screen.
 */
interface IShellService {

    /** Reserved teardown hook called by the Shizuku server. */
    void destroy() = 16777114;

    /** @return pid of the user service process when alive, 0 otherwise. */
    int ping() = 1;

    /** Capability report, see SelfTestReport.parse. */
    String selfTest() = 2;

    /** Runs a shell command, returning an ExecResult-encoded string. */
    String exec(String command, long timeoutMs) = 3;

    /** Injects a tap at (x, y) on the given display. Returns an InputEngine code. */
    int tap(int x, int y, int displayId) = 4;

    /** Injects a swipe on the given display. Returns an InputEngine code. */
    int swipe(int x1, int y1, int x2, int y2, int durationMs, int displayId) = 5;

    /** Closes the persistent fallback shell. */
    int closeFallbackShell() = 6;

    /** Starts a gesture (mirror drag) on the given display. */
    int pointerDown(int x, int y, int displayId) = 7;

    /** Continues a gesture. Ignored unless a matching pointerDown is active. */
    int pointerMove(int x, int y, int displayId) = 8;

    /** Ends a gesture. */
    int pointerUp(int x, int y, int displayId) = 9;
}
