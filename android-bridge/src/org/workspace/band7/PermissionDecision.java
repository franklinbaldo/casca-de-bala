package org.workspace.band7;
/** Dialog policy only; permission remains an Android decision. */
public final class PermissionDecision {
    public enum Action { CONTINUE, REQUEST, SETTINGS }
    private PermissionDecision() {}
    public static final class State {
        public final boolean granted,asked,rationale;
        public State(boolean granted,boolean asked,boolean rationale){this.granted=granted;this.asked=asked;this.rationale=rationale;}
    }
    public static Action discovery(boolean requireScan,State connect,State scan){
        if(connect.granted&&(!requireScan||scan.granted))return Action.CONTINUE;
        if(!connect.granted&&connect.asked&&!connect.rationale)return Action.SETTINGS;
        if(requireScan&&!scan.granted&&scan.asked&&!scan.rationale)return Action.SETTINGS;
        return Action.REQUEST;
    }
    public static Action action(boolean granted,boolean requestedBefore,boolean rationale){
        if(granted)return Action.CONTINUE;
        return requestedBefore&&!rationale?Action.SETTINGS:Action.REQUEST;
    }
}
