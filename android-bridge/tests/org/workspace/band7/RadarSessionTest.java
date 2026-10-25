package org.workspace.band7;
import java.util.*;
import static org.workspace.band7.RadarSession.*;
/** Synthetic RSSI/callback clock only. No phone, radio, positions or health data. */
public final class RadarSessionTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    private static final class Fake implements Transport {
        int reads,closes;boolean accepted=true;
        public boolean read(long token){reads++;return accepted;}
        public void close(){closes++;}
    }
    private static RadarSession open(Fake f){RadarSession s=new RadarSession(f,v->{},Origin.SYNTHETIC);s.start(0);s.connected(s.generation(),0);return s;}
    private static void block(RadarSession s,long at,int... values){
        check(s.measure(at));for(int i=0;i<values.length;i++){if(i>0)s.tick(at+i*1100);s.read(s.generation(),values[i],0,at+i*1100+1);}
    }
    public static void main(String[] args){
        Fake f=new Fake();RadarSession s=open(f);block(s,0,-81,-80,-79,-80,-81,-79);
        Snapshot first=s.snapshot();check(first.stage==Stage.READY&&first.points.size()==1&&first.points.get(0).median==-80);
        check(first.points.get(0).samples.size()==6&&first.points.get(0).samples.get(0).callbackAtMs==1);
        check(first.points.get(0).quality==Quality.STABLE&&first.origin==Origin.SYNTHETIC);
        check(first.points.get(0).trend==Trend.FIRST&&first.points.get(0).deviation>0);
        block(s,7000,-61,-60,-59,-60,-61,-59);check(s.snapshot().points.get(1).trend==Trend.WARMER);
        block(s,14000,-91,-90,-89,-90,-91,-89);check(s.snapshot().points.get(2).trend==Trend.COLDER);
        check(first.points.size()==1);try{first.points.clear();throw new AssertionError();}catch(UnsupportedOperationException expected){}
        s.stop(Reason.CANCELLED,21000);int reads=f.reads;s.tick(22000);s.read(s.generation(),-20,0,22001);check(f.reads==reads&&f.closes==1);

        Fake no=new Fake();RadarSession timeout=open(no);check(timeout.measure(0));check(!timeout.measure(1));
        timeout.tick(2500);check(timeout.snapshot().stage==Stage.ENDED&&timeout.snapshot().reason==Reason.READ_TIMEOUT&&no.closes==1);
        check(timeout.snapshot().points.get(0).median==null&&timeout.snapshot().points.get(0).quality==Quality.NO_RSSI);
        timeout.read(timeout.generation(),-40,0,2600);check(timeout.snapshot().points.get(0).samples.isEmpty());
        RadarSession newSession=open(new Fake());newSession.read(timeout.generation(),-40,0,2700);check(newSession.snapshot().points.isEmpty());

        Fake rejected=new Fake();rejected.accepted=false;RadarSession missing=open(rejected);check(missing.measure(0));
        for(int i=1;i<6;i++)missing.tick(i*1100);
        check(missing.snapshot().points.size()==1&&missing.snapshot().points.get(0).median==null&&rejected.reads==6);
        check(missing.snapshot().points.get(0).failures==6&&missing.snapshot().stage==Stage.READY);
        RadarSession invalid=open(new Fake());block(invalid,0,127,128,-128,127,127,127);check(invalid.snapshot().points.get(0).samples.isEmpty());

        Fake disconnected=new Fake();RadarSession partial=open(disconnected);check(partial.measure(0));partial.read(partial.generation(),-70,0,1);
        partial.stop(Reason.DISCONNECTED,10);check(partial.snapshot().points.get(0).quality==Quality.LOW_SAMPLE&&partial.snapshot().points.get(0).reason==Reason.DISCONNECTED);
        Snapshot retained=partial.snapshot();check(retained.stage==Stage.ENDED&&retained.points.size()==1);
        partial.stop(Reason.BACKGROUND,20);check(disconnected.closes==1&&retained.points.size()==1);
        RadarSession rotated=open(new Fake());block(rotated,0,-70,-70,-70,-70,-70,-70);rotated.stop(Reason.BACKGROUND,7000);
        check(rotated.snapshot().points.size()==1&&rotated.snapshot().reason==Reason.BACKGROUND);

        Fake limited=new Fake();RadarSession bounds=open(limited);for(int i=0;i<8;i++)block(bounds,i*7000,-70,-70,-70,-70,-70,-70);
        check(bounds.snapshot().points.size()==8);check(!bounds.measure(60000)&&bounds.snapshot().reason==Reason.POINT_LIMIT&&limited.closes==1);
        Fake connect=new Fake();RadarSession connecting=new RadarSession(connect,v->{},Origin.SYNTHETIC);connecting.start(0);connecting.tick(15000);
        check(connecting.snapshot().reason==Reason.CONNECT_TIMEOUT&&connect.closes==1);
        RadarSession elapsed=open(new Fake());elapsed.tick(120000);check(elapsed.snapshot().reason==Reason.SESSION_LIMIT);
        RadarSession permission=open(new Fake());permission.stop(Reason.PERMISSION,1);check(permission.snapshot().stage==Stage.ENDED);
        RadarSession duplicate=open(new Fake());check(duplicate.measure(0));duplicate.read(duplicate.generation(),-80,0,1);duplicate.read(duplicate.generation(),-50,0,2);
        check(duplicate.snapshot().pendingSamples==1);duplicate.stop(Reason.CANCELLED,3);
        RadarSession delayed=open(new Fake());check(delayed.measure(0));delayed.tick(100);delayed.read(delayed.generation(),-70,0,90,110);
        check(delayed.snapshot().pendingSamples==1);delayed.stop(Reason.CANCELLED,120);
        check(delayed.snapshot().points.get(0).samples.get(0).callbackAtMs==90&&delayed.snapshot().points.get(0).samples.get(0).processedAtMs==110);
        RadarSession spread=open(new Fake());block(spread,0,-90,-40,-85,-45,-88,-42);check(spread.snapshot().points.get(0).quality==Quality.VARIABLE);
        RadarSession failedStatus=open(new Fake());check(failedStatus.measure(0));failedStatus.read(failedStatus.generation(),-60,5,1);failedStatus.stop(Reason.CANCELLED,2);check(failedStatus.snapshot().points.get(0).samples.isEmpty());
        final int[] closed={0};RadarSession denied=new RadarSession(new Transport(){public boolean read(long t){throw new SecurityException();}public void close(){closed[0]++;}},v->{},Origin.SYNTHETIC);
        denied.start(0);denied.connected(denied.generation(),0);denied.measure(1);check(denied.snapshot().reason==Reason.PERMISSION&&closed[0]==1);
        Fake beforeConnect=new Fake();RadarSession notConnected=new RadarSession(beforeConnect,v->{},Origin.SYNTHETIC);notConnected.start(0);check(!notConnected.measure(1)&&beforeConnect.reads==0);
        try{notConnected.start(2);throw new AssertionError();}catch(IllegalStateException expected){}
        RadarSession clock=open(new Fake());clock.tick(5);clock.tick(4);check(clock.snapshot().reason==Reason.CLOCK);
        System.out.println("PASS radar synthetic: blocks/statistics/trends, bounds, no callback/RSSI, stale generation/duplicates, interruption/rotation policy, failures and disconnect");
    }
}
