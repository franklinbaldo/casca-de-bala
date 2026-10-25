package org.workspace.band7;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import static org.workspace.band7.BasicReadSession.Key;
import static org.workspace.band7.ReadViewModel.*;

/** Reuses the existing bounded GATT JNI envelope; no BLE, IO or remote MCP transport.
 * Origin is asserted by the caller, not an attestation. Only finished snapshots.
 */
public final class BasicSnapshot {
    private BasicSnapshot() {}
    public static byte[] process(Snapshot snapshot,BasicReadSession.Origin origin,long id){
        return GattBasics.process(envelope(snapshot,origin,id));
    }
    public static byte[] envelope(Snapshot s,BasicReadSession.Origin origin,long id){
        Objects.requireNonNull(s);Objects.requireNonNull(origin);
        if(!s.isFinal())throw new IllegalStateException("Snapshot not finished");
        if(id<0)throw new IllegalArgumentException("id");
        String pnp=s.pnp==null?"null":"{\"vendor_source\":"+s.pnp.vendorSource+",\"vendor_id\":"+s.pnp.vendor+",\"product_id\":"+s.pnp.product+",\"product_version_bcd\":"+s.pnp.version+"}";
        String request="{\"version\":1,\"id\":"+id+",\"command\":{\"op\":\"snapshot\",\"snapshot\":{\"reader\":\""+
            (origin==BasicReadSession.Origin.SYNTHETIC?"synthetic":"our_app")+"\",\"stage\":\""+name(s.stage)+
            "\",\"cause\":\""+name(s.cause)+"\",\"read_at_unix_ms\":"+s.readAt+",\"fields\":{"+
            "\"battery\":"+field(s,Key.BATTERY,String.valueOf(s.battery))+",\"hardware\":"+field(s,Key.HARDWARE,quote(s.hardware))+
            ",\"software\":"+field(s,Key.SOFTWARE,quote(s.software))+",\"pnp\":"+field(s,Key.PNP,pnp)+"}}}}";
        byte[] bytes=request.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>32768)throw new IllegalArgumentException("snapshot limit");return bytes;
    }
    private static String name(Enum<?> value){return value.name().toLowerCase(Locale.ROOT);}
    private static String field(Snapshot s,Key k,String value){return "{\"state\":\""+name(s.fields.get(k))+"\",\"value\":"+value+"}";}
    private static String quote(String value){
        if(value==null)return "null";
        if(value.length()>64)throw new IllegalArgumentException("revision limit");
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))throw new IllegalArgumentException("surrogate");b.append(c).append(value.charAt(++i));}
            else if(Character.isLowSurrogate(c)||Character.isISOControl(c))throw new IllegalArgumentException("revision character");
            else {if(c=='"'||c=='\\')b.append('\\');b.append(c);}
        }
        return b.append('"').toString();
    }
}
