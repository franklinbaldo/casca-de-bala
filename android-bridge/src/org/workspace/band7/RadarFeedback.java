// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;
/** Pure presentation: no new measurements, position, distance, bearing or IO. */
public final class RadarFeedback {
    public enum State { CONNECTING, WAITING_RSSI, WARMUP, LIVE, STALE, PAUSED }
    public final State state;public final boolean active,fresh,strengthUsable;public final int count,attempts,windowId,bestId,recentCount;
    public final long generation,sampleAtMs,ageMs;public final Double filteredDbm,rawDbm,bestDbm;
    public final RadarSession.Trend trend;public final List<RadarSession.Point> history;
    private RadarFeedback(RadarSession.Snapshot s){
        generation=s.generation;active=s.stage!=RadarSession.Stage.ENDED;history=s.points;
        RadarSession.Sample latest=s.latestSample;sampleAtMs=latest==null?-1:latest.callbackAtMs;ageMs=latest==null?Long.MAX_VALUE:Math.max(0,s.observedAtMs-latest.callbackAtMs);
        fresh=active&&ageMs<=3500;rawDbm=latest==null?null:(double)latest.rssiDbm;
        List<Integer> recent=new ArrayList<>();for(int i=s.recentSamples.size()-1;i>=0&&recent.size()<5;i--){RadarSession.Sample p=s.recentSamples.get(i);if(s.observedAtMs-p.callbackAtMs<=6000)recent.add(p.rssiDbm);}
        Collections.sort(recent);int n=recent.size();recentCount=n;filteredDbm=n==0?null:n%2==0?(recent.get(n/2-1)+recent.get(n/2))/2.0:(double)recent.get(n/2);
        strengthUsable=fresh&&n>=3;
        RadarSession.Point last=history.isEmpty()?null:history.get(history.size()-1);
        boolean sampling=s.stage==RadarSession.Stage.MEASURING;
        count=sampling?s.pendingSamples:last==null?0:last.samples.size();attempts=sampling?s.attempts:last==null?0:last.attempts;
        windowId=sampling?s.completedCount+1:last==null?1:last.id;
        trend=strengthUsable&&last!=null?last.trend:RadarSession.Trend.INCONCLUSIVE;
        if(!active)state=State.PAUSED;else if(s.stage==RadarSession.Stage.CONNECTING)state=State.CONNECTING;
        else if(latest==null)state=State.WAITING_RSSI;else if(!fresh)state=State.STALE;else if(!strengthUsable)state=State.WARMUP;else state=State.LIVE;
        RadarSession.Point best=null;for(RadarSession.Point p:history)if(p.samples.size()>=3&&(best==null||p.median>best.median))best=p;
        bestId=best==null?0:best.id;bestDbm=best==null?null:best.median;
    }
    public static RadarFeedback from(RadarSession.Snapshot value){return new RadarFeedback(Objects.requireNonNull(value));}
    public double visualStrength(){return strengthUsable?Math.max(0,Math.min(1,(filteredDbm+110)/80)):0;}
}
