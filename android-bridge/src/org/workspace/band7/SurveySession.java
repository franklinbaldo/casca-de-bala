// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;

/** Local, thread-confined admission of observations to completed radar stops.
 * No Android calls, permissions, scan, persistence, coordinates estimator or IO.
 * Origin/clock/correlation IDs are asserted by a future trusted adapter, not attested.
 */
public final class SurveySession implements AutoCloseable {
    public enum Scope { POSITION, WIFI_REFERENCE, BLE_REFERENCE }
    public enum Kind { POSITION, WIFI_REFERENCE, BLE_REFERENCE }
    public enum Acquisition { NEW, CACHE, UNKNOWN }
    public enum Freshness { NEW_FOR_WINDOW, CACHED, OUTSIDE_WINDOW, WINDOW_EDGE_UNCERTAIN, UNKNOWN }
    public enum Provider { GNSS, NETWORK, FUSED, UNKNOWN }
    public enum Precision { APPROXIMATE, PRECISE, UNKNOWN }
    public enum Code { INVALID, CLOCK, STALE_SESSION, ORIGIN, CONSENT_REQUIRED, CONFLICT, LIMIT, CLOSED }
    public static final class Failure extends IllegalArgumentException {
        private static final long serialVersionUID=1L;public final Code code;
        Failure(Code code){super(code.name());this.code=code;}
    }
    private static void require(boolean ok,Code code){if(!ok)throw new Failure(code);}
    private static <T> T present(T value){require(value!=null,Code.INVALID);return value;}
    private static String token(String value){require(value!=null&&value.matches("[A-Za-z0-9._-]{1,64}"),Code.INVALID);return value;}
    public static long millisToNanos(long value){require(value>=0,Code.CLOCK);try{return Math.multiplyExact(value,1_000_000L);}catch(ArithmeticException e){throw new Failure(Code.CLOCK);}}
    public static long microsToNanos(long value){require(value>=0,Code.CLOCK);try{return Math.multiplyExact(value,1_000L);}catch(ArithmeticException e){throw new Failure(Code.CLOCK);}}
    public static final class Stamp {
        public final Long measuredNs,requestedNs;public final long receivedNs;public final Acquisition acquisition;public final String correlationGroup;
        public Stamp(Long measuredNs,long receivedNs,Long requestedNs,Acquisition acquisition,String correlationGroup){
            require(receivedNs>=0&&(measuredNs==null||measuredNs>=0&&measuredNs<=receivedNs)&&(requestedNs==null||requestedNs>=0&&requestedNs<=receivedNs),Code.CLOCK);
            this.measuredNs=measuredNs;this.receivedNs=receivedNs;this.requestedNs=requestedNs;this.acquisition=present(acquisition);this.correlationGroup=token(correlationGroup);
        }
    }
    public static final class Observation {
        public final String measurementRef,referenceRef;public final RadarSession.Origin origin;public final Stamp stamp;public final Kind kind;
        public final Provider provider;public final Precision precision;public final Double latitude,longitude,accuracyMeters;public final Integer rssiDbm;
        private Observation(String id,RadarSession.Origin origin,Stamp stamp,Kind kind,String reference,Provider provider,Double lat,Double lon,Double accuracy,Precision precision,Integer rssi){
            measurementRef=token(id);this.origin=present(origin);this.stamp=present(stamp);this.kind=present(kind);referenceRef=token(reference);
            this.provider=provider;this.precision=precision;latitude=lat;longitude=lon;accuracyMeters=accuracy;rssiDbm=rssi;
        }
        public static Observation position(String id,RadarSession.Origin origin,Stamp stamp,Provider provider,double latitude,double longitude,Double accuracyMeters,Precision precision){
            present(provider);present(precision);require(Double.isFinite(latitude)&&latitude>=-90&&latitude<=90&&Double.isFinite(longitude)&&longitude>=-180&&longitude<=180,Code.INVALID);
            require(accuracyMeters==null||Double.isFinite(accuracyMeters)&&accuracyMeters>=0,Code.INVALID);
            return new Observation(id,origin,stamp,Kind.POSITION,provider.name(),provider,latitude,longitude,accuracyMeters,precision,null);
        }
        public static Observation reference(String id,RadarSession.Origin origin,Stamp stamp,Kind kind,String localReference,int rssi){
            require(kind==Kind.WIFI_REFERENCE||kind==Kind.BLE_REFERENCE,Code.INVALID);require(rssi>=-127&&rssi<=126,Code.INVALID);
            return new Observation(id,origin,stamp,kind,localReference,null,null,null,null,null,rssi);
        }
        private String key(){return kind+"|"+referenceRef+"|"+(stamp.measuredNs==null?"unknown:"+measurementRef:stamp.measuredNs);}
        private List<Object> payload(){return Arrays.asList(kind,referenceRef,provider,latitude,longitude,accuracyMeters,precision,rssiDbm,origin);}
    }
    public static final class Attached {
        public final Observation observation;public final Freshness freshness;public final Long ageAtDeliveryNs;
        Attached(Observation value,Freshness freshness){observation=value;this.freshness=freshness;ageAtDeliveryNs=value.stamp.measuredNs==null?null:value.stamp.receivedNs-value.stamp.measuredNs;}
    }
    public static final class Window {
        public final RadarSession.Point radar;public final List<Attached> observations;public final Set<String> correlationGroups;
        Window(RadarSession.Point point,Collection<Attached> values){radar=point;observations=Collections.unmodifiableList(new ArrayList<>(values));Set<String> groups=new LinkedHashSet<>();for(Attached a:values)groups.add(a.observation.stamp.correlationGroup);correlationGroups=Collections.unmodifiableSet(groups);}
    }
    public static final class Admission {
        public final Window window;public final int inserted,duplicateDeliveries,cachedDuplicateDeliveries,newForWindow;
        Admission(Window window,int inserted,int duplicate,int cached,int fresh){this.window=window;this.inserted=inserted;duplicateDeliveries=duplicate;cachedDuplicateDeliveries=cached;newForWindow=fresh;}
    }
    private static Freshness freshness(Observation obs,long start,long end){
        if(obs.stamp.acquisition==Acquisition.CACHE)return Freshness.CACHED;
        if(obs.stamp.acquisition==Acquisition.UNKNOWN||obs.stamp.measuredNs==null||obs.stamp.requestedNs==null)return Freshness.UNKNOWN;
        if(obs.stamp.measuredNs<obs.stamp.requestedNs)return Freshness.CACHED;
        if(obs.stamp.measuredNs<start||obs.stamp.measuredNs>=end+1_000_000L)return Freshness.OUTSIDE_WINDOW;
        // Radar0.4 boundaries have millisecond resolution. Do not invent nanos
        // precision when a reported position falls in either boundary millisecond.
        if(obs.stamp.measuredNs<start+1_000_000L||obs.stamp.measuredNs>=end)return Freshness.WINDOW_EDGE_UNCERTAIN;
        return Freshness.NEW_FOR_WINDOW;
    }
    private final long generation;private final RadarSession.Origin origin;private final EnumSet<Scope> consent;
    private final Map<Integer,Window> windows=new LinkedHashMap<>();private boolean closed;private long lastNowNs;
    public SurveySession(RadarSession.Snapshot baseline,Set<Scope> consent){present(baseline);require(baseline.mode==RadarSession.Mode.MANUAL,Code.INVALID);generation=baseline.generation;origin=baseline.origin;this.consent=EnumSet.noneOf(Scope.class);this.consent.addAll(present(consent));}
    public Window window(int point){return windows.get(point);}
    public void revoke(Scope scope){consent.remove(present(scope));}
    @Override public void close(){closed=true;} // retain immutable results; no further admission
    public Admission attach(RadarSession.Snapshot radar,int point,List<Observation> batch,long nowNs){
        require(!closed,Code.CLOSED);present(radar);present(batch);require(radar.generation==generation,Code.STALE_SESSION);require(radar.origin==origin,Code.ORIGIN);
        require(nowNs>=lastNowNs,Code.CLOCK);require(batch.size()<=64,Code.LIMIT);
        RadarSession.Point target=null;for(RadarSession.Point p:radar.points)if(p.id==point)target=p;
        require(target!=null,Code.INVALID);long start=millisToNanos(target.startedAtMs),end=millisToNanos(target.endedAtMs);require(end<=Long.MAX_VALUE-1_000_000L&&nowNs>=end,Code.CLOCK);
        Window previous=windows.get(point);require(previous==null||previous.radar==target,Code.CONFLICT);
        Map<String,Attached> staged=new LinkedHashMap<>();if(previous!=null)for(Attached a:previous.observations)staged.put(a.observation.key(),a);
        int inserted=0,duplicate=0,cached=0,freshCount=0;
        for(Observation obs:new ArrayList<>(batch)){
            present(obs);require(obs.origin==origin,Code.ORIGIN);require(obs.stamp.receivedNs<=nowNs,Code.CLOCK);
            Scope scope=obs.kind==Kind.POSITION?Scope.POSITION:obs.kind==Kind.WIFI_REFERENCE?Scope.WIFI_REFERENCE:Scope.BLE_REFERENCE;
            require(consent.contains(scope),Code.CONSENT_REQUIRED);
            Freshness fresh=freshness(obs,start,end);Attached found=staged.get(obs.key());
            if(found!=null){require(found.observation.payload().equals(obs.payload()),Code.CONFLICT);duplicate++;if(fresh==Freshness.CACHED)cached++;continue;}
            staged.put(obs.key(),new Attached(obs,fresh));inserted++;if(fresh==Freshness.NEW_FOR_WINDOW)freshCount++;require(staged.size()<=64,Code.LIMIT);
        }
        require(previous!=null||windows.size()<8,Code.LIMIT);
        Window committed=new Window(target,staged.values());windows.put(point,committed);lastNowNs=nowNs;return new Admission(committed,inserted,duplicate,cached,freshCount);
    }
}
