package org.workspace.band7;
import java.util.*;
import static org.workspace.band7.BasicReadSession.Key;

/** Typed presentation state, no Bluetooth, JSON parser or persistence. */
public final class ReadViewModel {
    public enum Stage { CONNECTING, DISCOVERING, READING, COMPLETE, PARTIAL, EMPTY, FAILED, INTERRUPTED }
    public enum Cause { NONE, PERMISSION, BLUETOOTH_OFF, UNAVAILABLE, CONNECTION, DISCOVERY, TIMEOUT, GATT, INVALID_REPLY, NATIVE, CANCELLED, BACKGROUND, SERVICE_CHANGED }
    public enum Availability { PENDING, READ, NOT_PRESENT, NOT_READ, INVALID, DENIED, GATT_ERROR }
    public static final class Pnp {
        public final int vendorSource,vendor,product,version;
        public Pnp(int vendorSource,int vendor,int product,int version){this.vendorSource=vendorSource;this.vendor=vendor;this.product=product;this.version=version;}
    }
    public static final class Snapshot {
        public final Stage stage;public final Cause cause;public final Integer battery;public final int receivedCount;
        public final String hardware,software;public final Pnp pnp;public final Long readAt;
        public final Map<Key,Availability> fields;
        public boolean isFinal(){return stage==Stage.COMPLETE||stage==Stage.PARTIAL||stage==Stage.EMPTY||stage==Stage.FAILED||stage==Stage.INTERRUPTED;}
        Snapshot(ReadViewModel m){stage=m.stage;cause=m.cause;battery=m.battery;hardware=m.hardware;software=m.software;pnp=m.pnp;readAt=m.readAt;fields=Collections.unmodifiableMap(new EnumMap<>(m.fields));int count=0;for(Availability state:fields.values())if(state==Availability.READ)count++;receivedCount=count;}
    }
    private Stage stage=Stage.CONNECTING;private Cause cause=Cause.NONE;
    private Integer battery;private String hardware,software;private Pnp pnp;private Long readAt;
    private final EnumMap<Key,Availability> fields=new EnumMap<>(Key.class);
    public ReadViewModel(){for(Key k:Key.values())fields.put(k,Availability.PENDING);}
    public Snapshot snapshot(){return new Snapshot(this);}
    public boolean terminal(){return stage==Stage.COMPLETE||stage==Stage.PARTIAL||stage==Stage.EMPTY||stage==Stage.FAILED||stage==Stage.INTERRUPTED;}
    public void discovering(){if(!terminal())stage=Stage.DISCOVERING;}
    public void reading(){if(!terminal())stage=Stage.READING;}
    public void unavailable(Key k,Availability state){if(!terminal()&&state!=Availability.READ&&state!=Availability.PENDING)fields.put(k,state);}
    public void battery(int value,long at){if(terminal())return;if(value<0||value>100)throw new IllegalArgumentException("battery");battery=value;success(Key.BATTERY,at);}
    public void revision(Key key,String value,long at){if(terminal())return;if(key==Key.HARDWARE)hardware=value;else if(key==Key.SOFTWARE)software=value;else throw new IllegalArgumentException("revision");success(key,at);}
    public void pnp(Pnp value,long at){if(!terminal()){pnp=Objects.requireNonNull(value);success(Key.PNP,at);}}
    private void success(Key key,long at){fields.put(key,Availability.READ);readAt=at;}
    public void finish(Cause reason){
        if(terminal())return;cause=reason;int count=0;
        for(Key key:Key.values()){if(fields.get(key)==Availability.READ)count++;else if(fields.get(key)==Availability.PENDING)fields.put(key,Availability.NOT_READ);}
        if(count==4&&reason==Cause.NONE)stage=Stage.COMPLETE;
        else if(count>0)stage=Stage.PARTIAL;
        else if(reason==Cause.NONE)stage=Stage.EMPTY;
        else if(reason==Cause.CANCELLED||reason==Cause.BACKGROUND)stage=Stage.INTERRUPTED;
        else stage=Stage.FAILED;
    }
}
