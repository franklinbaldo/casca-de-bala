package org.workspace.band7;
import java.util.*;
import static org.workspace.band7.RadarSession.*;
public final class RadarContinuousTest {
    private static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        final List<Long> requests=new ArrayList<>();final int[] closes={0};final long[] clock={0};
        RadarSession s=new RadarSession(new Transport(){public boolean read(long g){requests.add(clock[0]);return true;}public void close(){closes[0]++;}},v->{},Origin.SYNTHETIC,Mode.CONTINUOUS);
        s.start(0);s.connected(s.generation(),0);
        for(int i=0;i<66;i++){clock[0]=i*1200;s.tick(clock[0]);int n=requests.size();s.tick(clock[0]+100);check(requests.size()==n);s.read(s.generation(),-80+i%5,0,clock[0]+101);}
        Snapshot snap=s.snapshot();check(snap.mode==Mode.CONTINUOUS&&snap.points.size()==8&&snap.points.get(0).id==4&&snap.completedCount==11);
        check(snap.recentSamples.size()==12&&snap.latestSample!=null&&requests.size()==66);
        for(int i=1;i<requests.size();i++)check(requests.get(i)-requests.get(i-1)>=1000);
        clock[0]=121000;s.tick(clock[0]);check(s.snapshot().stage!=Stage.ENDED); // foreground stream is not manual2min limit
        s.stop(Reason.CANCELLED,121100);int before=requests.size();s.tick(122000);check(closes[0]==1&&requests.size()==before);
        RadarSession timeout=new RadarSession(new Transport(){public boolean read(long g){return true;}public void close(){}},v->{},Origin.SYNTHETIC,Mode.CONTINUOUS);
        timeout.start(0);timeout.connected(timeout.generation(),0);timeout.tick(0);timeout.tick(2500);check(timeout.snapshot().reason==Reason.READ_TIMEOUT);
        timeout.read(timeout.generation(),-40,0,2600);check(timeout.snapshot().latestSample==null);
        System.out.println("PASS continuous synthetic: automatic bounded windows, <=1Hz, one in-flight, rolling retention, foreground cancel, timeout and stale callback");
    }
}
