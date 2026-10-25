package org.workspace.band7;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Android-compatible Java call layer. Own this session on a supervised worker,
 * not a UI coroutine whose cancellation discards a native open result. */
public final class ActivitySession implements AutoCloseable {
    private long handle;
    public static final class NativeFailure extends RuntimeException {
        private static final long serialVersionUID=1L;
        public final int status;
        NativeFailure(int status) { super("Band7 native status " + status); this.status=status; }
    }
    public static final class Coverage {
        public final boolean hasCursor;
        public final long cursor, records, missing;
        Coverage(long[] result){hasCursor=result[1]!=0;cursor=result[3];records=result[5];missing=result[6];}
    }
    private ActivitySession() {}
    public static ActivitySession open(File privateDirectory,String deviceRef,String firmware,
                                       boolean synthetic,long start,long end) throws IOException {
        if(privateDirectory==null||!privateDirectory.isDirectory())throw new IllegalArgumentException("private directory required");
        // Allocate owner before native open; input is newly encoded, never shared.
        ActivitySession owner=new ActivitySession();
        File database=new File(privateDirectory.getCanonicalFile(),"activity8.sqlite");
        long[] result=call("{\"op\":\"open\",\"path\":"+quote(database.getPath())+
            ",\"device_ref\":"+quote(deviceRef)+",\"firmware\":"+quote(firmware)+
            ",\"synthetic\":"+synthetic+",\"start\":"+start+",\"end\":"+end+"}");
        owner.handle=result[2];
        if(owner.handle==0)throw new IllegalStateException("invalid native handle");
        return owner;
    }
    public synchronized long ingest(long first,Integer utcOffsetSeconds,byte[] activity8){
        requireOpen();
        if(activity8==null||activity8.length>32768)throw new IllegalArgumentException("payload limit");
        char[] hex=new char[activity8.length*2];String digits="0123456789abcdef";
        for(int i=0;i<activity8.length;i++){int b=activity8[i]&255;hex[i*2]=digits.charAt(b>>>4);hex[i*2+1]=digits.charAt(b&15);}
        long[] result=call("{\"op\":\"ingest\",\"handle\":"+Long.toUnsignedString(handle)+
            ",\"first\":"+first+",\"utc_offset_seconds\":"+utcOffsetSeconds+",\"activity8_hex\":\""+new String(hex)+"\"}");
        return result[4];
    }
    public synchronized Coverage coverage(){requireOpen();return new Coverage(call("{\"op\":\"coverage\",\"handle\":"+Long.toUnsignedString(handle)+"}"));}
    @Override public synchronized void close(){
        if(handle==0)return;
        long[] result=NativeBridge.invoke(envelope("{\"op\":\"close\",\"handle\":"+Long.toUnsignedString(handle)+"}"));
        validateShape(result);
        if(result[0]==0||result[0]==2){handle=0;return;}
        throw new NativeFailure((int)result[0]);
    }
    private void requireOpen(){if(handle==0)throw new IllegalStateException("session closed");}
    private static long[] call(String operation){long[] r=NativeBridge.invoke(envelope(operation));validateShape(r);if(r[0]!=0)throw new NativeFailure((int)r[0]);return r;}
    private static void validateShape(long[] r){if(r==null||r.length!=8)throw new IllegalStateException("JNI result shape");}
    private static byte[] envelope(String operation){byte[] bytes=("{\"version\":1,\"command\":"+operation+"}").getBytes(StandardCharsets.UTF_8);if(bytes.length>70000)throw new IllegalArgumentException("request limit");return bytes;}
    private static String quote(String value){
        if(value==null||value.length()>4096)throw new IllegalArgumentException("string limit");
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<value.length();i++){char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))throw new IllegalArgumentException("unpaired surrogate");b.append(c).append(value.charAt(++i));}
            else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("unpaired surrogate");
            else if(c=='"'||c=='\\')b.append('\\').append(c);
            else if(c<32){String h=Integer.toHexString(c);b.append("\\u");for(int j=h.length();j<4;j++)b.append('0');b.append(h);}
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
