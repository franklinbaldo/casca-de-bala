package org.workspace.band7;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import static org.workspace.band7.BasicReadSession.Key;
import static org.workspace.band7.ReadViewModel.*;
/** Synthetic values, real JVM/JNI/Rust and CLI process. No Android or radio. */
public final class BasicSnapshotSmoke {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        ReadViewModel model=new ReadViewModel();check(!model.snapshot().isFinal());model.battery(0,123456789L);
        model.revision(Key.HARDWARE,"lab-α\"\\",123456790L);
        model.unavailable(Key.SOFTWARE,Availability.DENIED);model.finish(Cause.TIMEOUT);
        Snapshot snapshot=model.snapshot();check(snapshot.isFinal());
        byte[] request=BasicSnapshot.envelope(snapshot,BasicReadSession.Origin.SYNTHETIC,73);
        byte[] result=BasicSnapshot.process(snapshot,BasicReadSession.Origin.SYNTHETIC,73);
        String json=new String(result,StandardCharsets.UTF_8);
        check(json.contains("\"id\":73"));check(json.contains("\"value\":0"));
        check(json.contains("\"reader\":\"synthetic\""));check(json.contains("123456790"));
        check(json.contains("\"stage\":\"partial\""));check(json.contains("\"cause\":\"timeout\""));
        check(json.contains("cached_snapshot"));check(json.contains("denied"));
        Process cli=new ProcessBuilder(args[0],"--gatt-json").start();
        cli.getOutputStream().write(request);cli.getOutputStream().close();
        check(cli.waitFor(5,TimeUnit.SECONDS));check(cli.exitValue()==0);
        check(Arrays.equals(result,new String(cli.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8)));
        byte[] unchanged=result.clone();BasicSnapshot.process(snapshot,BasicReadSession.Origin.SYNTHETIC,74);check(Arrays.equals(result,unchanged));
        ReadViewModel empty=new ReadViewModel();empty.finish(Cause.NONE);check(empty.snapshot().isFinal());
        String noData=new String(BasicSnapshot.process(empty.snapshot(),BasicReadSession.Origin.SYNTHETIC,75),StandardCharsets.UTF_8);
        check(noData.contains("\"received_count\":0"));check(noData.contains("\"read_at_unix_ms\":null"));
        try{BasicSnapshot.envelope(new ReadViewModel().snapshot(),BasicReadSession.Origin.SYNTHETIC,1);throw new AssertionError();}catch(IllegalStateException expected){}
        ReadViewModel complete=new ReadViewModel();complete.battery(75,100);complete.revision(Key.HARDWARE,"lab-hw",101);
        complete.revision(Key.SOFTWARE,"lab-sw",102);complete.pnp(new Pnp(2,11,22,0x0102),103);complete.finish(Cause.NONE);
        String all=new String(BasicSnapshot.process(complete.snapshot(),BasicReadSession.Origin.SYNTHETIC,76),StandardCharsets.UTF_8);
        check(all.contains("\"vendor_source\":2"));check(all.contains("\"received_count\":4"));
        check(all.contains("\"stage\":\"complete\""));
        System.out.println("PASS synthetic model -> snapshot -> real JNI/Rust -> CLI replay; no Android/physical/MCP transport");
    }
}
