package org.workspace.casca;

/** Pure stats from already stored samples; no Android or device dependencies. */
final class CascaHealthStats {
    static final class Snapshot {
        final int min, max, count, latest;
        final long average, latestAt;
        Snapshot(int min, int max, int count, long average, int latest, long latestAt) {
            this.min=min;this.max=max;this.count=count;this.average=average;
            this.latest=latest;this.latestAt=latestAt;
        }
    }
    private int min=Integer.MAX_VALUE,max,count,latest;
    private long sum,latestAt;

    void add(int value,long at){
        min=Math.min(min,value);max=Math.max(max,value);sum+=value;count++;
        if(at>=latestAt){latestAt=at;latest=value;}
    }
    Snapshot snapshot(){
        if(count==0)return null;
        return new Snapshot(min,max,count,Math.round((double)sum/count),latest,latestAt);
    }
}
