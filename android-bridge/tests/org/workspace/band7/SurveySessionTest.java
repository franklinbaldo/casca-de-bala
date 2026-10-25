package org.workspace.band7;
import java.util.*;
import static org.workspace.band7.SurveySession.*;
/** Synthetic coordinates/references only; joins the real radar coordinator, no IO. */
public final class SurveySessionTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    private static void failure(Code code,Runnable action){try{action.run();throw new AssertionError("Expected "+code);}catch(Failure e){check(e.code==code);}}
    private static RadarSession radar(){
        RadarSession r=new RadarSession(new RadarSession.Transport(){public boolean read(long generation){return true;}public void close(){}},s->{},RadarSession.Origin.SYNTHETIC);
        r.start(0);r.connected(r.generation(),0);r.measure(1000);
        for(int i=0;i<6;i++){if(i>0)r.tick(1000+i*1100);r.read(r.generation(),-70,0,1001+i*1100);}return r;
    }
    private static Stamp stamp(Long at,long received,Acquisition acquisition,String group){return new Stamp(at,received,1_000_000_000L,acquisition,group);}
    private static Observation position(String id,Stamp stamp,double lat,Double accuracy){return Observation.position(id,RadarSession.Origin.SYNTHETIC,stamp,Provider.GNSS,lat,20,accuracy,Precision.APPROXIMATE);}
    private static Observation wifi(String id,long at,String group){return Observation.reference(id,RadarSession.Origin.SYNTHETIC,stamp(at,7_000_000_000L,Acquisition.NEW,group),Kind.WIFI_REFERENCE,id,-60);}
    public static void main(String[] args){
        RadarSession radar=radar();RadarSession.Snapshot baseline=radar.snapshot();
        SurveySession manual=new SurveySession(baseline,EnumSet.noneOf(Scope.class));
        Window bare=manual.attach(baseline,1,Collections.emptyList(),8_000_000_000L).window;
        check(bare.observations.isEmpty()&&bare.radar.median==-70); // no invented position
        Observation fix=position("fix-1",stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"gps-block-1"),10,25.0);
        failure(Code.CONSENT_REQUIRED,()->manual.attach(baseline,1,Arrays.asList(fix),8_000_000_000L));
        SurveySession session=new SurveySession(baseline,EnumSet.allOf(Scope.class));
        Window first=session.attach(baseline,1,Arrays.asList(fix,wifi("wifi-1",2_000_000_000L,"scan-1"),wifi("wifi-2",2_000_000_000L,"scan-1")),8_000_000_000L).window;
        check(first.observations.size()==3&&first.correlationGroups.size()==2);
        check(first.observations.get(0).freshness==Freshness.NEW_FOR_WINDOW);
        check(first.observations.get(0).observation.accuracyMeters==25.0);
        Observation replay=position("cached-delivery",stamp(2_000_000_000L,8_000_000_000L,Acquisition.CACHE,"retrieval-2"),10,25.0);
        Admission replayed=session.attach(baseline,1,Arrays.asList(replay),9_000_000_000L);
        check(replayed.window.observations.size()==3&&replayed.inserted==0&&replayed.newForWindow==0&&replayed.duplicateDeliveries==1&&replayed.cachedDuplicateDeliveries==1);
        check(first.observations.size()==3); // immutable, duplicate retrieval is not a new fix
        Observation old=position("old",stamp(500_000_000L,7_000_000_000L,Acquisition.NEW,"gps-cache"),10,40.0);
        Observation outside=position("outside",stamp(7_000_000_000L,7_100_000_000L,Acquisition.NEW,"gps-late"),10,40.0);
        Observation unknown=position("unknown",stamp(null,7_000_000_000L,Acquisition.UNKNOWN,"gps-unknown"),10,null);
        Window added=session.attach(baseline,1,Arrays.asList(old,outside,unknown),9_000_000_000L).window;
        check(added.observations.get(3).freshness==Freshness.CACHED);
        check(added.observations.get(4).freshness==Freshness.OUTSIDE_WINDOW);
        check(added.observations.get(5).freshness==Freshness.UNKNOWN);
        check(added.observations.get(5).observation.accuracyMeters==null);
        int count=added.observations.size();
        Observation another=position("another",stamp(3_000_000_000L,7_000_000_000L,Acquisition.NEW,"gps-block-1"),10,30.0);
        Observation conflict=position("conflict",stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"gps-block-1"),11,25.0);
        failure(Code.CONFLICT,()->session.attach(baseline,1,Arrays.asList(another,conflict),9_000_000_000L));
        check(session.window(1).observations.size()==count); // atomic batch
        failure(Code.STALE_SESSION,()->session.attach(radar().snapshot(),1,Arrays.asList(fix),9_000_000_000L));
        Observation wrongOrigin=Observation.reference("ble-1",RadarSession.Origin.OUR_APP,stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"scan-1"),Kind.BLE_REFERENCE,"beacon-1",-60);
        failure(Code.ORIGIN,()->session.attach(baseline,1,Arrays.asList(wrongOrigin),9_000_000_000L));
        failure(Code.CLOCK,()->session.attach(baseline,1,Arrays.asList(fix),6_000_000_000L));
        failure(Code.INVALID,()->position("bad",stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"g"),Double.NaN,1.0));
        failure(Code.INVALID,()->position("bad",stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"g"),91,1.0));
        failure(Code.INVALID,()->position("bad",stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"g"),10,-1.0));
        failure(Code.CLOCK,()->new Stamp(8L,7L,null,Acquisition.CACHE,"g"));
        failure(Code.CLOCK,()->millisToNanos(Long.MAX_VALUE));
        failure(Code.INVALID,()->Observation.reference("bad",RadarSession.Origin.SYNTHETIC,stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"g"),Kind.POSITION,"wifi-1",-60));
        List<Observation> many=new ArrayList<>();for(int i=0;i<65;i++)many.add(wifi("wifi-"+i,2_000_000_000L+i,"scan-1"));
        failure(Code.LIMIT,()->session.attach(baseline,1,many,9_000_000_000L));check(session.window(1).observations.size()==count);
        check(microsToNanos(123)==123000&&millisToNanos(123)==123000000);
        failure(Code.CLOCK,()->microsToNanos(Long.MAX_VALUE));
        failure(Code.INVALID,()->Observation.position("bad-longitude",RadarSession.Origin.SYNTHETIC,stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"g"),Provider.FUSED,10,181,1.0,Precision.PRECISE));
        failure(Code.INVALID,()->position("infinite",stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"g"),10,Double.POSITIVE_INFINITY));
        check(first.observations.get(1).observation.latitude==null&&first.observations.get(1).observation.longitude==null);
        SurveySession bounded=new SurveySession(baseline,EnumSet.of(Scope.WIFI_REFERENCE));
        List<Observation> full=new ArrayList<>();for(int i=0;i<64;i++)full.add(wifi("wifi-"+i,2_000_000_000L+i,"scan-1"));
        check(bounded.attach(baseline,1,full,8_000_000_000L).window.observations.size()==64);
        failure(Code.LIMIT,()->bounded.attach(baseline,1,Arrays.asList(wifi("extra",2_000_001_000L,"scan-2")),9_000_000_000L));
        check(bounded.window(1).observations.size()==64);
        SurveySession edges=new SurveySession(baseline,EnumSet.of(Scope.POSITION));
        Observation edge=position("edge",stamp(1_000_000_000L,7_000_000_000L,Acquisition.NEW,"edge"),10,25.0);
        Admission edgeResult=edges.attach(baseline,1,Arrays.asList(edge),8_000_000_000L);
        check(edgeResult.newForWindow==0&&edgeResult.window.observations.get(0).freshness==Freshness.WINDOW_EDGE_UNCERTAIN);
        Observation ble=Observation.reference("ble-1",RadarSession.Origin.SYNTHETIC,stamp(2_000_000_000L,7_000_000_000L,Acquisition.NEW,"ble-scan-1"),Kind.BLE_REFERENCE,"beacon-1",-65);
        SurveySession bleOnly=new SurveySession(baseline,EnumSet.of(Scope.BLE_REFERENCE));
        Admission bleAccepted=bleOnly.attach(baseline,1,Arrays.asList(ble),8_000_000_000L);
        check(bleAccepted.inserted==1&&bleAccepted.window.observations.get(0).observation.kind==Kind.BLE_REFERENCE&&bleAccepted.window.observations.get(0).observation.latitude==null);
        failure(Code.CONSENT_REQUIRED,()->bleOnly.attach(baseline,1,Arrays.asList(wifi("wifi-1",2_000_000_000L,"scan-1")),9_000_000_000L));
        check(bleOnly.window(1).observations.size()==1);
        session.revoke(Scope.POSITION);failure(Code.CONSENT_REQUIRED,()->session.attach(baseline,1,Arrays.asList(another),9_000_000_000L));
        session.close();failure(Code.CLOSED,()->session.attach(baseline,1,Collections.emptyList(),9_000_000_000L));
        check(session.window(1).observations.size()==count);
        try{first.observations.clear();throw new AssertionError();}catch(UnsupportedOperationException expected){}
        System.out.println("PASS synthetic survey: radar join, no invented position, consent/origin/time, freshness/cache, correlation, dedup, atomic conflict, bounds and close");
    }
}
