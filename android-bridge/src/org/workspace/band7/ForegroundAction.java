package org.workspace.band7;
/** A user-requested find action resumes once after permissions/settings, never
 * after an ordinary background interruption. Contains no device identifiers. */
public final class ForegroundAction {
    public enum Wait { NONE, PERMISSION, SETTINGS }
    private boolean foreground,pending;private Wait wait=Wait.NONE;
    public boolean clicked(boolean granted){pending=true;return consume(granted);}
    public void external(Wait next){pending=true;wait=next;}
    public boolean permissionResult(boolean granted){wait=Wait.NONE;if(!granted)pending=false;return consume(granted);}
    public boolean resume(boolean granted){foreground=true;if(wait==Wait.SETTINGS){wait=Wait.NONE;if(!granted)pending=false;}return consume(granted);}
    public void stop(){foreground=false;if(wait==Wait.NONE)pending=false;}
    public boolean waiting(){return wait!=Wait.NONE;}
    public void cancel(){pending=false;wait=Wait.NONE;}
    private boolean consume(boolean granted){if(foreground&&pending&&wait==Wait.NONE&&granted){pending=false;return true;}return false;}
}
