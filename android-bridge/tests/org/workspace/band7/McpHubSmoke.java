package org.workspace.band7;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.workspace.band7.ReadViewModel.*;

/** Real JVM -> JNI -> Rust MCP server -> loopback HTTP. Synthetic snapshot; no Android. */
public final class McpHubSmoke {
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    private static String field(String json,String regex){Matcher m=Pattern.compile(regex).matcher(json);check(m.find(),regex+" in "+json);return m.group(1);}
    public static void main(String[] args)throws Exception{
        String dir=Files.createTempDirectory("casca-mcp-jni").toString();
        check(McpHub.quote("a\"b\\c\n").equals("\"a\\\"b\\\\c\\u000a\""),"quote");
        String started=McpHub.start(dir,0);int port=Integer.parseInt(field(started,"\"port\":(\\d+)"));
        check(McpHub.start(dir,0).contains("already_running"),"single server");
        HttpClient http=HttpClient.newHttpClient();URI mcp=URI.create("http://127.0.0.1:"+port+"/mcp");
        String ping="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        check(http.send(HttpRequest.newBuilder(mcp).POST(HttpRequest.BodyPublishers.ofString(ping)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==401,"no token");
        try(Socket socket=new Socket("127.0.0.1",port)){
            String body="{\"client\":\"jni-smoke\"}";OutputStream out=socket.getOutputStream();
            out.write(("POST /pair HTTP/1.1\r\nHost: x\r\nContent-Length: "+body.length()+"\r\n\r\n"+body).getBytes(StandardCharsets.UTF_8));out.flush();
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String line;while(!(line=in.readLine()).isEmpty()){}
            String prompt=in.readLine();String status=McpHub.status();
            String code=field(status,"\"code\":(\\d+)");check(prompt.contains("código "+code+" "),"code shown both sides");
            check(McpHub.decide(Long.parseLong(field(status,"\"id\":(\\d+)")),true).contains("\"ok\":true"),"decide");
            check(in.readLine().startsWith("Aprovado"),"approved");String token=field(in.readLine(),"CASCA_TOKEN=(\\S+)");
            ReadViewModel model=new ReadViewModel();model.battery(74,100);model.finish(Cause.NONE);
            check(McpHub.publish(BasicSnapshot.process(model.snapshot(),BasicReadSession.Origin.SYNTHETIC,9)).contains("\"ok\":true"),"publish");
            String call="{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"band_snapshot\",\"arguments\":{}}}";
            HttpResponse<String> reply=http.send(HttpRequest.newBuilder(mcp).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString(call)).build(),HttpResponse.BodyHandlers.ofString());
            check(reply.statusCode()==200&&reply.body().contains("\\\"value\\\": 74"),reply.body());
        }
        check(McpHub.stop().contains("\"ok\":true"),"stop");check(McpHub.status().contains("\"running\":false"),"stopped");
        System.out.println("PASS JVM -> JNI -> Rust MCP: pairing code on both sides, approval, token, snapshot tool over loopback HTTP, stop");
    }
}
