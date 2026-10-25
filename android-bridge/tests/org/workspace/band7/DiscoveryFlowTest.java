package org.workspace.band7;
import java.util.*;
import static org.workspace.band7.DiscoverySession.*;
import static org.workspace.band7.BasicReadSession.Key;
import static org.workspace.band7.ReadViewModel.*;

/** Synthetic policy tests; no Android stack, radio, personal IDs or health data. */
public final class DiscoveryFlowTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    private static final class Fake implements Transport,DiscoverySession.Observer {
        int starts,stops,selections;boolean denyStart,failStart;long token;Status status;
        List<Row> rows=Collections.emptyList();String selected;
        public void start(long generation){starts++;token=generation;if(denyStart)throw new SecurityException();if(failStart)throw new IllegalStateException();}
        public void stop(long generation){stops++;check(generation==token);}
        public void changed(long generation,Status next,List<Row> value){status=next;rows=value;}
        public void selected(String address,String label){selections++;selected=address;}
        DiscoverySession model(){return new DiscoverySession(this,this);}
    }
    private static void discovery(){
        Fake f=new Fake();DiscoverySession s=f.model();s.start(true,true,true,0);long first=s.generation();
        check(f.starts==1&&f.status==Status.SEARCHING);
        s.result(first,"fixture-a","Band",1);s.result(first,"fixture-b","Band",2);
        s.result(first,"fixture-a","Band",3);check(f.rows.size()==2&&f.rows.get(0).id!=f.rows.get(1).id);
        try{f.rows.clear();throw new AssertionError();}catch(UnsupportedOperationException expected){}
        check(f.selections==0);check(s.select(first,2,true,true));check(f.selections==1&&f.selected.equals("fixture-b")&&f.stops==1&&!s.active());
        check(!s.select(first,2,true,true));s.result(first,"late","late",4);check(f.rows.isEmpty());
        s.start(true,true,true,10);long second=s.generation();check(second!=first);
        s.result(first,"old","old",11);check(f.rows.isEmpty());s.result(second,"fixture-c",null,12);check(f.rows.get(0).label.isEmpty());
        s.tick(12010,true,true);check(f.status==Status.TIMED_OUT&&!s.active()&&f.rows.size()==1);
        check(s.select(second,1,true,true));check(f.selected.equals("fixture-c"));
        s.start(true,true,true,13000);long cancelled=s.generation();s.cancel();s.cancel();
        s.result(cancelled,"late","late",13001);check(f.rows.isEmpty()&&!s.select(cancelled,1,true,true));
        Fake empty=new Fake();DiscoverySession e=empty.model();e.start(true,true,true,0);e.tick(12000,true,true);check(empty.status==Status.TIMED_OUT&&empty.rows.isEmpty());
        Fake limit=new Fake();DiscoverySession l=limit.model();l.start(true,true,true,0);
        for(int i=0;i<33;i++)l.result(l.generation(),"synthetic-"+i,"name",1);
        check(limit.rows.size()==32&&limit.status==Status.LIMIT&&limit.stops==1);
        Fake deadline=new Fake();DiscoverySession d=deadline.model();d.start(true,true,true,0);d.result(d.generation(),"too-late","name",12000);check(deadline.rows.isEmpty()&&deadline.status==Status.TIMED_OUT);
        check(safeName("\nBand "+String.join(":",Collections.nCopies(6,"AA"))+"\t").equals("Band"));
        check(safeName("Band DEADBEEF").equals("Band"));check(safeName("abcdef123456").isEmpty());check(safeName(null).isEmpty());
    }
    private static void failures(){
        for(int mode=0;mode<7;mode++){
            Fake f=new Fake();DiscoverySession s=f.model();f.denyStart=mode==3;f.failStart=mode==4;
            s.start(mode!=0,mode!=1,mode!=2,0);
            if(mode==0){check(f.status==Status.PERMISSION_DENIED&&f.starts==0);continue;}
            if(mode==1){check(f.status==Status.BLUETOOTH_OFF&&f.starts==0);continue;}
            if(mode==2){check(f.status==Status.UNAVAILABLE&&f.starts==0);continue;}
            if(mode==3){check(f.status==Status.PERMISSION_DENIED&&f.stops==1);continue;}
            if(mode==4){check(f.status==Status.FAILED&&f.stops==1);continue;}
            s.result(s.generation(),"fixture","name",1);
            if(mode==5)s.tick(2,false,true);else s.tick(2,true,false);
            check(f.rows.isEmpty()&&!s.active()&&f.stops==1);
        }
        Fake f=new Fake();DiscoverySession s=f.model();s.start(true,true,true,0);long old=s.generation();s.start(true,true,true,1);
        check(f.stops==1&&f.starts==2);s.fail(old);s.permissionLost(old);check(s.active());s.fail(s.generation());check(f.status==Status.FAILED&&!s.active());
        s.start(true,true,true,2);s.permissionLost(s.generation());check(f.status==Status.PERMISSION_DENIED&&!s.active());
        Fake select=new Fake();DiscoverySession r=select.model();r.start(true,true,true,0);r.result(r.generation(),"fixture","name",1);
        check(!r.select(r.generation(),1,false,true)&&select.selections==0);
    }
    private static void presentation(){
        ReadViewModel partial=new ReadViewModel();check(partial.snapshot().battery==null);partial.discovering();partial.reading();
        partial.battery(0,123);partial.unavailable(Key.SOFTWARE,Availability.NOT_PRESENT);partial.finish(Cause.TIMEOUT);
        check(partial.snapshot().stage==Stage.PARTIAL&&partial.snapshot().battery==0&&partial.snapshot().software==null&&partial.snapshot().readAt==123);
        partial.battery(99,456);partial.finish(Cause.CONNECTION);check(partial.snapshot().battery==0&&partial.snapshot().cause==Cause.TIMEOUT);
        ReadViewModel full=new ReadViewModel();full.reading();full.battery(91,1);full.revision(Key.HARDWARE,"synthetic-hw",2);full.revision(Key.SOFTWARE,"synthetic-sw",3);full.pnp(new Pnp(1,1,2,3),4);full.finish(Cause.NONE);
        full.finish(Cause.CONNECTION);check(full.snapshot().stage==Stage.COMPLETE&&full.snapshot().battery==91&&full.snapshot().cause==Cause.NONE);
        ReadViewModel unavailable=new ReadViewModel();unavailable.finish(Cause.NONE);check(unavailable.snapshot().battery==null&&unavailable.snapshot().stage==Stage.EMPTY&&unavailable.snapshot().receivedCount==0);
        unavailable.battery(99,999);check(unavailable.snapshot().receivedCount==0&&unavailable.snapshot().battery==null);
        ReadViewModel stopped=new ReadViewModel();stopped.finish(Cause.BACKGROUND);check(stopped.snapshot().stage==Stage.INTERRUPTED);
        ReadViewModel failed=new ReadViewModel();failed.finish(Cause.PERMISSION);check(failed.snapshot().stage==Stage.FAILED);
    }
    private static void permissions(){
        check(PermissionDecision.action(true,true,false)==PermissionDecision.Action.CONTINUE);
        check(PermissionDecision.action(false,false,false)==PermissionDecision.Action.REQUEST);
        check(PermissionDecision.action(false,true,true)==PermissionDecision.Action.REQUEST);
        check(PermissionDecision.action(false,true,false)==PermissionDecision.Action.SETTINGS);
        ForegroundAction a=new ForegroundAction();check(!a.resume(false));check(!a.clicked(false));a.external(ForegroundAction.Wait.PERMISSION);a.stop();check(a.waiting());
        check(!a.permissionResult(true));check(a.resume(true));check(!a.resume(true));
        ForegroundAction b=new ForegroundAction();b.resume(false);b.clicked(false);b.external(ForegroundAction.Wait.PERMISSION);b.stop();check(!b.resume(true));check(b.permissionResult(true));check(!b.permissionResult(true));
        b.clicked(false);b.external(ForegroundAction.Wait.SETTINGS);b.stop();check(b.resume(true));check(!b.resume(true));
        b.clicked(false);b.external(ForegroundAction.Wait.SETTINGS);b.stop();check(!b.resume(false));check(!b.resume(true));
        ForegroundAction denied=new ForegroundAction();denied.resume(false);denied.clicked(false);denied.external(ForegroundAction.Wait.PERMISSION);check(!denied.permissionResult(false));check(!denied.resume(true));
        ForegroundAction ordinary=new ForegroundAction();ordinary.clicked(false);ordinary.stop();check(!ordinary.resume(true));
    }
    private static void localFollowup(){
        Fake f=new Fake();DiscoverySession s=f.model();s.start(true,true,true,0);long token=s.generation();
        s.result(token,"synthetic-near","Band",-45,1);s.result(token,"synthetic-weak","Band",-90,2);s.result(token,"synthetic-unnamed",null,127,3);
        check(f.rows.size()==3&&f.rows.get(0).rssi==-45&&f.rows.get(1).rssi==-90&&f.rows.get(2).rssi==null);
        check(f.rows.get(0).label.equals(f.rows.get(1).label)&&f.rows.get(0).id!=f.rows.get(1).id&&f.rows.get(2).label.isEmpty());
        s.result(token,"synthetic-near","Band",-20,4);check(f.rows.get(0).rssi==-45); // stable initial estimate, no reordering/focus churn
        s.result(token,"synthetic-unnamed",null,-75,5);check(f.rows.get(2).rssi==-75&&f.selections==0);
        s.cancel();s.start(true,true,true,6);s.result(token,"old","old",-10,7);check(f.rows.isEmpty());
        s.result(s.generation(),"new","Band",-128,8);check(f.rows.get(0).rssi==null&&f.selections==0);
        for(Cause cause:new Cause[]{Cause.NONE,Cause.TIMEOUT,Cause.CANCELLED,Cause.PERMISSION}){
            ReadViewModel empty=new ReadViewModel();empty.finish(cause);check(empty.snapshot().receivedCount==0&&empty.snapshot().battery==null&&empty.snapshot().readAt==null);
            ReadViewModel some=new ReadViewModel();some.battery(0,1);some.finish(cause);check(some.snapshot().receivedCount==1&&some.snapshot().stage==Stage.PARTIAL&&some.snapshot().battery==0);
        }
    }
    public static void main(String[] args){discovery();failures();presentation();permissions();localFollowup();System.out.println("PASS synthetic discovery/selection, bounded scan, stale callbacks, permissions/settings resume, empty vs partial, signal estimates, disconnect preservation");}
}
