package org.workspace.band7;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-confined, one-shot controller. Android adapter must serialize all calls.
 * Transport is replaceable in host tests; decoding always uses the real Rust JNI.
 */
public final class BasicReadSession implements AutoCloseable {
    public enum Key {
        HARDWARE("hardware_revision",0x180a,0x2a27),
        SOFTWARE("software_revision",0x180a,0x2a28),
        PNP("pnp_id",0x180a,0x2a50), BATTERY("battery",0x180f,0x2a19);
        public final String kind; public final int service, characteristic;
        Key(String kind,int service,int characteristic){this.kind=kind;this.service=service;this.characteristic=characteristic;}
        public static UUID sig(int number){return UUID.fromString(String.format(Locale.ROOT,"0000%04x-0000-1000-8000-00805f9b34fb",number));}
        public static Key from(UUID service,UUID characteristic){
            for(Key k:values())if(sig(k.service).equals(service)&&sig(k.characteristic).equals(characteristic))return k;
            return null;
        }
    }
    public enum Origin { SYNTHETIC, OUR_APP }
    public interface Transport { boolean read(long generation,Key key); void close(); }
    public interface Observer { void result(Key key,String json); void stopped(String reason); }
    private static final AtomicLong GENERATIONS=new AtomicLong();
    private final long generation=GENERATIONS.incrementAndGet();
    private final Transport transport; private final Observer observer; private final String origin;
    private final ArrayDeque<Key> queue=new ArrayDeque<>();
    private Key pending; private long deadline; private boolean started,closed;
    public BasicReadSession(Transport transport,Observer observer,Origin origin){
        this.transport=Objects.requireNonNull(transport);this.observer=Objects.requireNonNull(observer);
        this.origin=Objects.requireNonNull(origin)==Origin.SYNTHETIC?"synthetic":"our_app";
    }
    public long generation(){return generation;}
    /** Keys come from the native plan, not arbitrary discovered characteristics. */
    public void start(List<Key> plan,boolean permitted,long now){
        if(started||closed)throw new IllegalStateException("One-shot session");
        Objects.requireNonNull(plan);
        if(plan.size()>4||plan.contains(null)||new HashSet<>(plan).size()!=plan.size())throw new IllegalArgumentException("Invalid plan");
        started=true;
        if(!permitted){stop("permission_denied");return;}
        queue.addAll(plan);next(now);
    }
    private void emit(Key key,String outcome){
        String request="{\"version\":1,\"id\":"+generation+",\"command\":{\"op\":\"interpret\",\"kind\":\""+key.kind+"\",\"reader\":\""+origin+"\",\"outcome\":"+outcome+"}}";
        observer.result(key,new String(GattBasics.process(request.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));
    }
    private void next(long now){
        if(closed)return;
        pending=queue.poll();
        if(pending==null){stop("plan_finished_not_full_collection");return;}
        deadline=now+10000;
        try{if(!transport.read(generation,pending)){emit(pending,"{\"state\":\"not_attempted\"}");stop("read_not_started");}}
        catch(SecurityException denied){emit(pending,"{\"state\":\"permission_denied\"}");stop("permission_denied");}
    }
    public void read(long callbackGeneration,Key key,int status,byte[] value,long now){
        if(closed||callbackGeneration!=generation||key!=pending||pending==null)return;
        if(now>=deadline){stop("timeout");return;}
        if(status<0||value==null||value.length>64){stop("invalid_callback");return;}
        StringBuilder data=new StringBuilder("{\"state\":\"gatt\",\"status\":").append(status).append(",\"bytes\":[");
        for(int i=0;i<value.length;i++){if(i>0)data.append(',');data.append(value[i]&255);}
        data.append("]}");emit(key,data.toString());pending=null;
        if(status!=0){stop("gatt_error");return;}
        next(now);
    }
    public void tick(long now){if(!closed&&pending!=null&&now>=deadline)stop("timeout");}
    public void stop(String reason){
        if(closed)return;closed=true;pending=null;queue.clear();
        try{transport.close();}finally{observer.stopped(reason);}
    }
    @Override public void close(){stop("cancelled");}
}
