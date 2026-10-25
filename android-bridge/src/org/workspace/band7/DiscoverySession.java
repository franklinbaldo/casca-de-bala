package org.workspace.band7;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground, thread-confined discovery policy; transport is Android or synthetic.
 * Full addresses stay private and transient; snapshots contain only names/row IDs.
 */
public final class DiscoverySession {
    public enum Status { SEARCHING, READING_BONDED, BONDED_READY, TIMED_OUT, CANCELLED, SELECTED, PERMISSION_DENIED, BLUETOOTH_OFF, BLUETOOTH_CHANGING, UNAVAILABLE, FAILED, LIMIT }
    public enum Radio { ON, OFF, CHANGING, UNAVAILABLE, DENIED }
    public enum Kind { LE, DUAL, UNKNOWN, CLASSIC }
    public static final class Bonded {
        private final String address,name;private final Kind kind;
        public Bonded(String address,String name,Kind kind){this.address=address;this.name=name;this.kind=kind==null?Kind.UNKNOWN:kind;}
    }
    public interface Transport { void start(long generation); void stop(long generation); }
    public interface Observer { void changed(long generation,Status status,List<Row> rows); void selected(String address,String label); }
    public static final class Row {
        public final int id;public final String label;public final Integer rssi;public final boolean bonded,seenNow;public final Kind kind;
        Row(Entry e){id=e.id;label=e.name;rssi=e.rssi;bonded=e.bonded;seenNow=e.seenNow;kind=e.kind;}
    }
    private static final AtomicLong GENERATIONS=new AtomicLong();
    private static final class Entry { final int id;String name;Integer rssi;boolean bonded,seenNow;Kind kind;
        Entry(int id,String name,Integer rssi){this.id=id;this.name=name;this.rssi=rssi;this.kind=Kind.LE;this.seenNow=true;} }
    private final Transport transport;private final Observer observer;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private long generation,deadline;private boolean active,readingBonded;private Status status=Status.CANCELLED;
    public DiscoverySession(Transport transport,Observer observer){this.transport=Objects.requireNonNull(transport);this.observer=Objects.requireNonNull(observer);}
    public boolean active(){return active;}
    public boolean readingBonded(){return readingBonded;}
    public boolean knownKey(long token,String key){return token==generation&&entries.containsKey(key);}
    public long generation(){return generation;}
    public void start(boolean permitted,boolean enabled,boolean available,long now){
        cancelSilently();generation=GENERATIONS.incrementAndGet();
        entries.entrySet().removeIf(item->!item.getValue().bonded);
        for(Entry e:entries.values()){e.seenNow=false;e.rssi=null;}
        if(!permitted){status=Status.PERMISSION_DENIED;publish();return;}
        if(!available){status=Status.UNAVAILABLE;publish();return;}
        if(!enabled){status=Status.BLUETOOTH_OFF;publish();return;}
        active=true;status=Status.SEARCHING;deadline=now+12000;publish();
        try{transport.start(generation);}
        catch(SecurityException denied){stop(Status.PERMISSION_DENIED,true);}
        catch(IllegalStateException failed){stop(Status.FAILED,true);}
    }
    public long beginBonded(boolean connectPermission,Radio radio,long now){
        cancelSilently();generation=GENERATIONS.incrementAndGet();entries.clear();
        if(!connectPermission){status=Status.PERMISSION_DENIED;publish();return generation;}
        if(radio!=Radio.ON){radioChanged(generation,radio);return generation;}
        readingBonded=true;status=Status.READING_BONDED;deadline=now+5000;publish();return generation;
    }
    public void bonded(long token,List<Bonded> devices){
        if(token!=generation||!readingBonded)return;
        boolean limited=false;
        for(Bonded d:devices){
            if(d==null||d.kind==Kind.CLASSIC||d.address==null||d.address.isEmpty()||d.address.length()>80)continue;
            Entry e=entries.get(d.address);
            if(e==null){if(entries.size()==32){limited=true;break;}e=new Entry(entries.size()+1,safeName(d.name),null);entries.put(d.address,e);}
            e.name=safeName(d.name);e.bonded=true;e.seenNow=false;e.kind=d.kind;
        }
        readingBonded=false;status=limited?Status.LIMIT:Status.BONDED_READY;publish();
    }
    public void bondedFailure(long token,boolean denied){if(token==generation&&readingBonded)stop(denied?Status.PERMISSION_DENIED:Status.FAILED,true);}
    public void radioChanged(long token,Radio radio){
        if(token!=generation||radio==Radio.ON)return;
        stop(radio==Radio.DENIED?Status.PERMISSION_DENIED:radio==Radio.UNAVAILABLE?Status.UNAVAILABLE:radio==Radio.OFF?Status.BLUETOOTH_OFF:Status.BLUETOOTH_CHANGING,true);
    }
    public void result(long token,String address,String name,long now){result(token,address,name,null,now);}
    public void result(long token,String address,String name,Integer rssi,long now){
        if(!active||token!=generation)return;
        if(now>=deadline){stop(Status.TIMED_OUT,false);return;}
        if(address==null||address.isEmpty()||address.length()>80)return;
        Entry e=entries.get(address);
        Integer signal=rssi!=null&&rssi>=-127&&rssi<=126?rssi:null;
        if(e==null){if(entries.size()==32){stop(Status.LIMIT,false);return;}e=new Entry(entries.size()+1,safeName(name),signal);entries.put(address,e);}
        else {String clean=safeName(name);if(!clean.isEmpty())e.name=clean;if(e.rssi==null)e.rssi=signal;}
        e.seenNow=true;if(e.kind==Kind.UNKNOWN)e.kind=Kind.LE;
        publish();
    }
    public void tick(long now,boolean permitted,boolean enabled){
        if(!active&&!readingBonded)return;
        if(!permitted)stop(Status.PERMISSION_DENIED,true);
        else if(!enabled)stop(Status.BLUETOOTH_OFF,true);
        else if(now>=deadline)stop(Status.TIMED_OUT,false);
    }
    public void fail(long token){if(active&&token==generation)stop(Status.FAILED,false);}
    public void permissionLost(long token){if((active||readingBonded||!entries.isEmpty())&&token==generation)stop(Status.PERMISSION_DENIED,true);}
    public boolean select(long token,int id,boolean permitted,boolean enabled){
        return select(token,id,permitted,enabled,false);
    }
    public boolean select(long token,int id,boolean permitted,boolean enabled,boolean allowUnknown){
        if(token!=generation||status==Status.CANCELLED||status==Status.SELECTED)return false;
        if(!permitted){stop(Status.PERMISSION_DENIED,true);return false;}
        if(!enabled){stop(Status.BLUETOOTH_OFF,true);return false;}
        for(Map.Entry<String,Entry> item:entries.entrySet())if(item.getValue().id==id){
            if(item.getValue().kind==Kind.UNKNOWN&&!item.getValue().seenNow&&!allowUnknown)return false;
            String address=item.getKey(),label=item.getValue().name;
            stop(Status.SELECTED,true);observer.selected(address,label);return true;
        }
        return false;
    }
    public void cancel(){stop(Status.CANCELLED,true);generation=GENERATIONS.incrementAndGet();}
    private void cancelSilently(){readingBonded=false;if(active){active=false;try{transport.stop(generation);}catch(SecurityException ignored){}}}
    private void stop(Status next,boolean clear){cancelSilently();status=next;if(clear)entries.clear();publish();}
    private void publish(){List<Row> rows=new ArrayList<>();for(Entry e:entries.values())rows.add(new Row(e));observer.changed(generation,status,Collections.unmodifiableList(rows));}
    public static String safeName(String name){
        if(name==null)return "";
        String limited=name.substring(0,Math.min(name.length(),128)).replaceAll("[\\p{Cntrl}]","").trim();
        limited=limited.replaceAll("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}","");
        // Common unit-specific hexadecimal suffixes are unnecessary in screenshots.
        limited=limited.replaceAll("(?i)[ _-]+[0-9a-f]{8,}$","").trim();
        if(limited.isEmpty()||limited.matches("(?i)[0-9a-f]{8,}"))return "";
        return limited.substring(0,Math.min(limited.length(),48));
    }
}
