package org.workspace.casca;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** The MCP hub in Rust (crates/casca-mcp via crates/casca-mcp-jni). JSON in,
 * JSON out; failures carry "error". The server runs on Rust threads between
 * start and stop. */
final class McpNative {
    static final int DEFAULT_PORT=8765;
    static { System.loadLibrary("casca_mcp"); }
    private McpNative() {}
    private static native byte[] control(byte[] request);

    static JSONObject call(JSONObject request)throws JSONException{
        byte[] reply=control(request.toString().getBytes(StandardCharsets.UTF_8));
        if(reply==null)throw new JSONException("native mcp call failed");
        return new JSONObject(new String(reply,StandardCharsets.UTF_8));
    }
    static JSONObject op(String op)throws JSONException{return call(new JSONObject().put("op",op));}
    static void log(String line){
        try{call(new JSONObject().put("op","log").put("line",line));}catch(JSONException|RuntimeException|LinkageError ignored){}
    }
}
