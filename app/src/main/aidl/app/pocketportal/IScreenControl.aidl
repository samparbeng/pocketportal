package app.pocketportal;

// Runs inside Shizuku's shell-privileged process (see ScreenControlService).
interface IScreenControl {
    // Shizuku calls this to tear the service down; the transaction code is fixed by Shizuku.
    void destroy() = 16777114;

    // Switches the phone's physical panel off or back on while Android keeps drawing
    // (and screen capture keeps running). Returns false if the device doesn't allow it.
    boolean setPanelOn(boolean on) = 1;
}
