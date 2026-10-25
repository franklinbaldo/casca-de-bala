package org.workspace.band7;
import java.util.*;
import static org.workspace.band7.DiscoverySession.*;

/** Synthetic saved-bond records, never a phone or radio integration test. */
public final class BondedFlowTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    private static final class Fake implements Transport,DiscoverySession.Observer {
        int scans,stops,selections;Status status;List<Row> rows=Collections.emptyList();String chosen;
        public void start(long token){scans++;}public void stop(long token){stops++;}
        public void changed(long token,Status status,List<Row> rows){this.status=status;this.rows=rows;}
        public void selected(String address,String label){selections++;chosen=address;}
    }
    public static void main(String[] args){
        Fake f=new Fake();DiscoverySession m=new DiscoverySession(f,f);
        long token=m.beginBonded(true,Radio.ON,0);check(m.readingBonded()&&f.scans==0&&f.status==Status.READING_BONDED);
        m.bonded(token,Arrays.asList(new Bonded("fixture-band","Band",Kind.LE),new Bonded("fixture-tv","TV",Kind.CLASSIC),new Bonded("fixture-dual","Dual",Kind.DUAL),new Bonded("fixture-unknown",null,Kind.UNKNOWN)));
        check(f.rows.size()==3&&f.selections==0&&f.scans==0&&f.status==Status.BONDED_READY);
        check(f.rows.get(0).bonded&&!f.rows.get(0).seenNow&&f.rows.get(0).rssi==null);
        check(f.rows.get(2).label.isEmpty()&&f.rows.get(2).kind==Kind.UNKNOWN);
        check(m.select(token,1,true,true)&&f.chosen.equals("fixture-band")&&f.scans==0);
        m.beginBonded(true,Radio.ON,1);token=m.generation();m.bonded(token,Arrays.asList(new Bonded("fixture-unknown",null,Kind.UNKNOWN)));
        check(!m.select(token,1,true,true)&&f.selections==1);check(m.select(token,1,true,true,true)&&f.selections==2);
        token=m.beginBonded(true,Radio.ON,2);m.bonded(token,Arrays.asList(new Bonded("fixture-band","Band",Kind.LE)));
        int stable=f.rows.get(0).id;m.start(true,true,true,3);long scan=m.generation();
        check(f.rows.size()==1&&f.rows.get(0).bonded&&!f.rows.get(0).seenNow);
        m.result(scan,"fixture-band","Band",-45,4);check(f.rows.size()==1&&f.rows.get(0).id==stable&&f.rows.get(0).bonded&&f.rows.get(0).seenNow);
        check(m.select(scan,stable,true,true)&&f.stops==1);m.result(scan,"late","late",5);check(f.rows.isEmpty());
        long old=m.beginBonded(true,Radio.ON,6);long newer=m.beginBonded(true,Radio.ON,7);
        m.bonded(old,Arrays.asList(new Bonded("stale","stale",Kind.LE)));m.bondedFailure(old,true);check(m.readingBonded()&&f.rows.isEmpty());
        m.bonded(newer,Collections.emptyList());check(f.status==Status.BONDED_READY&&f.rows.isEmpty());
        for(Radio radio:new Radio[]{Radio.OFF,Radio.CHANGING,Radio.UNAVAILABLE,Radio.DENIED}){
            token=m.beginBonded(true,radio,8);check(!m.readingBonded()&&f.rows.isEmpty()&&f.status!=Status.BONDED_READY);
            m.bonded(token,Collections.emptyList());check(f.status!=Status.BONDED_READY);
        }
        token=m.beginBonded(false,Radio.ON,9);check(f.status==Status.PERMISSION_DENIED&&!m.readingBonded());
        token=m.beginBonded(true,Radio.ON,10);m.tick(5010,true,true);check(f.status==Status.TIMED_OUT&&!m.readingBonded());
        m.bonded(token,Arrays.asList(new Bonded("too-late","Band",Kind.LE)));check(f.rows.isEmpty());
        token=m.beginBonded(true,Radio.ON,20);m.bondedFailure(token,false);check(f.status==Status.FAILED);
        token=m.beginBonded(true,Radio.ON,30);m.bondedFailure(token,true);check(f.status==Status.PERMISSION_DENIED);
        token=m.beginBonded(true,Radio.ON,40);m.bonded(token,Arrays.asList(new Bonded("fixture","Band",Kind.LE)));check(!m.select(token,1,false,true)&&f.status==Status.PERMISSION_DENIED);
        token=m.beginBonded(true,Radio.ON,50);m.cancel();m.bonded(token,Arrays.asList(new Bonded("late","Band",Kind.LE)));check(f.rows.isEmpty());
        PermissionDecision.State yes=new PermissionDecision.State(true,true,false),scanDenied=new PermissionDecision.State(false,true,false),fresh=new PermissionDecision.State(false,false,false);
        check(PermissionDecision.discovery(false,yes,scanDenied)==PermissionDecision.Action.CONTINUE);
        check(PermissionDecision.discovery(true,yes,scanDenied)==PermissionDecision.Action.SETTINGS);
        check(PermissionDecision.discovery(true,yes,fresh)==PermissionDecision.Action.REQUEST);
        check(PermissionDecision.discovery(false,fresh,scanDenied)==PermissionDecision.Action.REQUEST);
        ForegroundAction action=new ForegroundAction();action.resume(false);action.clicked(false);action.external(ForegroundAction.Wait.PERMISSION);action.stop();
        check(!action.permissionResult(true));check(action.resume(true));check(!action.resume(true));
        final int[] results={0};final String[] stopped={null};
        BasicReadSession outOfRange=new BasicReadSession(new BasicReadSession.Transport(){
            public boolean read(long generation,BasicReadSession.Key key){return true;}public void close(){}
        },new BasicReadSession.Observer(){public void result(BasicReadSession.Key key,String json){results[0]++;}public void stopped(String reason){stopped[0]=reason;}},BasicReadSession.Origin.SYNTHETIC);
        outOfRange.start(Arrays.asList(BasicReadSession.Key.BATTERY),true,0);outOfRange.tick(10000);
        check(results[0]==0&&"timeout".equals(stopped[0]));
        ReadViewModel noRead=new ReadViewModel();noRead.finish(ReadViewModel.Cause.TIMEOUT);check(noRead.snapshot().receivedCount==0&&noRead.snapshot().stage==ReadViewModel.Stage.FAILED);
        System.out.println("PASS synthetic bonded-only, CONNECT without SCAN, LE/DUAL/unknown/classic, dedup/origins, radio transitions, revocation, timeout and stale callbacks");
    }
}
