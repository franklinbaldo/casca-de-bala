package org.workspace.band7;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Controls the phone's MCP server in the Rust core. JSON in, JSON out: every
 * reply is an object and failures carry "error"; callers parse it. The server
 * runs on Rust threads between start and stop, independent of any Activity.
 */
public final class McpHub {
    public static final int DEFAULT_PORT=8765;
    private McpHub() {}
    public static String start(String dir,int port){return call("{\"op\":\"start\",\"dir\":"+quote(dir)+",\"port\":"+port+"}");}
    public static String stop(){return call("{\"op\":\"stop\"}");}
    public static String status(){return call("{\"op\":\"status\"}");}
    public static String decide(long id,boolean approve){return call("{\"op\":\"decide\",\"id\":"+id+",\"approve\":"+approve+"}");}
    /** Jobs waiting for the app (each handed out once): {"jobs":[{"id","kind","args"}]}. */
    public static String jobs(String... prefixes){
        StringBuilder b=new StringBuilder("{\"op\":\"jobs\",\"prefixes\":[");
        for(int i=0;i<prefixes.length;i++){if(i>0)b.append(',');b.append(quote(prefixes[i]));}
        return call(b.append("]}").toString());
    }
    /** Appends a line to the lab log the AIs read with lab_log. */
    public static String log(String line){return call("{\"op\":\"log\",\"line\":"+quote(line)+"}");}
    /** Answers a job; resultJson must be a JSON object, {"error":"…"} for a readable failure. */
    public static String complete(long id,String resultJson){return call("{\"op\":\"complete\",\"id\":"+id+",\"result\":"+Objects.requireNonNull(resultJson)+"}");}
    public static String forget(String client){return call("{\"op\":\"forget\",\"client\":"+quote(client)+"}");}
    /** Takes the GATT bridge reply from BasicSnapshot.process, never raw GATT. */
    public static String publish(byte[] snapshotReply){
        Objects.requireNonNull(snapshotReply);
        return call("{\"op\":\"publish\",\"snapshot\":"+new String(snapshotReply,StandardCharsets.UTF_8)+"}");
    }
    private static String call(String request){
        byte[] reply=NativeBridge.mcp(request.getBytes(StandardCharsets.UTF_8));
        if(reply==null)throw new IllegalStateException("native mcp call failed");
        return new String(reply,StandardCharsets.UTF_8);
    }
    static String quote(String value){
        Objects.requireNonNull(value);
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='"'||c=='\\')b.append('\\').append(c);
            else if(c<0x20)b.append(String.format("\\u%04x",(int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
