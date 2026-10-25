package org.workspace.band7;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground connected-RSSI blocks. No positions, distance model, storage or GATT writes.
 * All calls on one thread; clock is elapsedRealtime milliseconds, not wall time.
 */
public final class RadarSession {
    public enum Origin { SYNTHETIC, OUR_APP }
    public enum Stage { CONNECTING, READY, MEASURING, ENDED }
    public enum Reason { NONE, CONNECT_TIMEOUT, READ_TIMEOUT, DISCONNECTED, PERMISSION, BLUETOOTH_OFF, UNAVAILABLE, FAILED, CANCELLED, BACKGROUND, SESSION_LIMIT, POINT_LIMIT, CLOCK }
    public enum Quality { STABLE, VARIABLE, LOW_SAMPLE, NO_RSSI }
    public enum Trend { FIRST, WARMER, COLDER, SIMILAR, INCONCLUSIVE }
    public interface Transport { boolean read(long generation);void close(); }
    public interface Observer { void changed(Snapshot snapshot); }
    public static final class Sample {
        public final long requestedAtMs,callbackAtMs,processedAtMs;public final int rssiDbm;
        Sample(long requested,long callback,long processed,int rssi){requestedAtMs=requested;callbackAtMs=callback;processedAtMs=processed;rssiDbm=rssi;}
    }
    public static final class Point {
        public final int id,attempts,failures;public final long startedAtMs,endedAtMs;
        public final List<Sample> samples;public final Double median,deviation;public final Quality quality;public final Trend trend;public final Reason reason;
        Point(int id,long start,long end,List<Sample> values,int attempts,int failures,Reason reason,Point previous){
            this.id=id;startedAtMs=start;endedAtMs=end;this.attempts=attempts;this.failures=failures;this.reason=reason;
            samples=Collections.unmodifiableList(new ArrayList<>(values));
            if(values.isEmpty()){median=null;deviation=null;quality=Quality.NO_RSSI;trend=Trend.INCONCLUSIVE;return;}
            int[] sorted=values.stream().mapToInt(s->s.rssiDbm).sorted().toArray();int n=sorted.length;
            median=n%2==0?(sorted[n/2-1]+sorted[n/2])/2.0:(double)sorted[n/2];
            double mean=Arrays.stream(sorted).average().orElseThrow(),sum=0;for(int v:sorted)sum+=(v-mean)*(v-mean);
            deviation=Math.sqrt(sum/n); // descriptive population spread, NOT uncertainty of independent samples
            quality=n<3?Quality.LOW_SAMPLE:deviation>6?Quality.VARIABLE:Quality.STABLE;
            if(previous==null)trend=Trend.FIRST;
            else if(n<3||previous.samples.size()<3)trend=Trend.INCONCLUSIVE;
            else {double threshold=Math.max(4,Math.min(12,(deviation+previous.deviation)/2));double delta=median-previous.median;
                trend=Math.abs(delta)<threshold?Trend.SIMILAR:delta>0?Trend.WARMER:Trend.COLDER;}
        }
    }
    public static final class Snapshot {
        public final Stage stage;public final Reason reason;public final Origin origin;
        public final List<Point> points;public final int pendingSamples,attempts;public final long generation;
        Snapshot(RadarSession m){stage=m.stage;reason=m.reason;origin=m.origin;generation=m.generation;points=Collections.unmodifiableList(new ArrayList<>(m.points));pendingSamples=m.samples.size();attempts=m.attempts;}
    }
    private static final AtomicLong GENERATIONS=new AtomicLong();
    private final long generation=GENERATIONS.incrementAndGet();private final Transport transport;private final Observer observer;private final Origin origin;
    private final List<Point> points=new ArrayList<>();private final List<Sample> samples=new ArrayList<>();
    private Stage stage=Stage.CONNECTING;private Reason reason=Reason.NONE;private boolean started,pending;
    private long lastNow,sessionEnd,connectEnd,blockStart,blockEnd,requestedAt,requestEnd,nextAt;private int attempts,failures;
    public RadarSession(Transport transport,Observer observer,Origin origin){this.transport=Objects.requireNonNull(transport);this.observer=Objects.requireNonNull(observer);this.origin=Objects.requireNonNull(origin);}
    public long generation(){return generation;}
    public Snapshot snapshot(){return new Snapshot(this);}
    public void start(long now){if(started)throw new IllegalStateException("one-shot");if(now<0||now>Long.MAX_VALUE-120000)throw new IllegalArgumentException("clock");started=true;lastNow=now;sessionEnd=now+120000;connectEnd=now+15000;publish();}
    private boolean clock(long now){if(!started)throw new IllegalStateException("not started");if(stage==Stage.ENDED)return false;if(now<lastNow){stop(Reason.CLOCK,lastNow);return false;}lastNow=now;return true;}
    public void connected(long token,long now){if(token!=generation||stage!=Stage.CONNECTING||!clock(now))return;if(now>=connectEnd){stop(Reason.CONNECT_TIMEOUT,now);return;}stage=Stage.READY;publish();}
    public boolean measure(long now){
        if(!clock(now)||stage!=Stage.READY)return false;
        if(now>=sessionEnd){stop(Reason.SESSION_LIMIT,now);return false;}
        if(points.size()>=8){stop(Reason.POINT_LIMIT,now);return false;}
        samples.clear();attempts=failures=0;blockStart=now;blockEnd=Math.min(sessionEnd,now+10000);stage=Stage.MEASURING;issue(now);return true;
    }
    private void issue(long now){
        pending=true;requestedAt=now;requestEnd=Math.min(blockEnd,now+2500);attempts++;publish();
        try{boolean accepted=transport.read(generation);if(!accepted&&stage==Stage.MEASURING&&pending){pending=false;failures++;advance(now);}}
        catch(SecurityException denied){stop(Reason.PERMISSION,now);}catch(IllegalStateException failed){stop(Reason.FAILED,now);}
    }
    public void read(long token,int rssi,int status,long now){read(token,rssi,status,now,now);}
    public void read(long token,int rssi,int status,long callbackAt,long now){
        if(token!=generation||stage!=Stage.MEASURING||!pending||!clock(now))return;
        if(now>=requestEnd){stop(Reason.READ_TIMEOUT,now);return;}
        if(callbackAt<requestedAt||callbackAt>now)return;
        pending=false;
        if(status==0&&rssi>=-127&&rssi<=126)samples.add(new Sample(requestedAt,callbackAt,now,rssi));else failures++;
        advance(now);
    }
    private void advance(long now){if(attempts>=6){finishPoint(now,Reason.NONE);stage=Stage.READY;publish();}else{nextAt=now+1000;publish();}}
    private void finishPoint(long now,Reason stopped){
        Point previous=null;for(int i=points.size()-1;i>=0;i--)if(points.get(i).median!=null){previous=points.get(i);break;}
        points.add(new Point(points.size()+1,blockStart,now,samples,attempts,failures,stopped,previous));samples.clear();pending=false;
    }
    public void tick(long now){
        if(!clock(now))return;
        if(now>=sessionEnd){stop(Reason.SESSION_LIMIT,now);return;}
        if(stage==Stage.CONNECTING&&now>=connectEnd){stop(Reason.CONNECT_TIMEOUT,now);return;}
        if(stage!=Stage.MEASURING)return;
        // RSSI callbacks have no per-request ID. A missing callback ends the GATT
        // session; never reuse it for a new point that could accept the late result.
        if(pending&&now>=requestEnd){stop(Reason.READ_TIMEOUT,now);return;}
        if(now>=blockEnd){stop(Reason.READ_TIMEOUT,now);return;}
        if(!pending&&now>=nextAt)issue(now);
    }
    public void stop(Reason why,long now){
        if(stage==Stage.ENDED)return;if(why==Reason.NONE)throw new IllegalArgumentException("stop reason");
        if(stage==Stage.MEASURING){if(pending)failures++;finishPoint(Math.max(now,lastNow),why);}
        stage=Stage.ENDED;reason=why;pending=false;
        try{transport.close();}finally{publish();}
    }
    private void publish(){observer.changed(snapshot());}
}
