package org.workspace.casca;

/** Thread-affinity contract. Keep Gadgetbridge device commands on Android's main thread.
 * Read-only database and Android queries, JSON conversion, Termux and JNI completion
 * must not block the main looper. New operations default to main until reviewed. */
final class CascaJobDispatch {
    private CascaJobDispatch() {}

    static boolean mainThreadRequired(String kind, String action) {
        switch (kind) {
            case "band.read":
            case "band.capabilities":
            case "gb.bluetooth":
            case "gb.phone":
            case "gb.shell":
            case "gb.shell.result":
                return false;
            case "band.lists":
                return "set".equals(action);
            case "band.devices":
            case "gb.devices":
            case "gb.discover":
            case "gb.inspect":
            case "band.notify":
            case "band.find":
            case "band.sync":
            case "band.settings":
            case "gb.invoke":
            default:
                return true;
        }
    }
}
