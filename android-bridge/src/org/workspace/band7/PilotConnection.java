// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
/** Typed product presentation boundary. No secret fields or serialization. */
public interface PilotConnection extends AutoCloseable {
 enum Stage { READY_TO_START,CONNECTING,AUTHENTICATING,READY,READING,COMPLETE,STOPPED,ERROR }
 enum Reason { NONE,SELECTION_REQUIRED,PERMISSION,AUTH_REJECTED,TIMEOUT,UNSUPPORTED,CANCELLED,BACKGROUND,TRANSPORT }
 enum Origin { DEVICE_UNVERIFIED,SIMULATED_PEER }
 final class State {
  public final Stage stage;public final Reason reason;public final Origin origin;public final Integer battery;public final long receivedElapsed;
  public State(Stage stage,Reason reason,Origin origin,Integer battery,long receivedElapsed){this.stage=java.util.Objects.requireNonNull(stage);this.reason=java.util.Objects.requireNonNull(reason);this.origin=java.util.Objects.requireNonNull(origin);if(battery!=null&&(battery<0||battery>100)||receivedElapsed< -1)throw new IllegalArgumentException("pilot state");this.battery=battery;this.receivedElapsed=receivedElapsed;}
 }
 void start();boolean requestBattery();@Override void close();
}
