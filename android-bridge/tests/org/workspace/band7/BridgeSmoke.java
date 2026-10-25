package org.workspace.band7;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public final class BridgeSmoke {
    private static void check(boolean condition){if(!condition)throw new AssertionError();}
    private static void status(byte[] bytes,int code){long[] r=NativeBridge.invoke(bytes);check(r.length==8&&r[0]==code);}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("b7-jni-synthetic-").toFile();
        // Non-ASCII + quotes/backslash exercise JSON escaping and real UTF-8 file paths.
        File dir=new File(root,"fixture-ç-\"-\\");check(dir.mkdir());
        for(String invalidSource : new String[] { "\uD800", new String(new char[4097]) }) {
            try { ActivitySession.open(dir,invalidSource,"test",true,120,240);throw new AssertionError(); }
            catch(IllegalArgumentException expected) {}
        }
        byte[] sample={1,2,3,(byte)255,5,6,7,8};
        ActivitySession first=ActivitySession.open(dir,"fixture","test",true,120,240);
        check(!first.coverage().hasCursor);
        check(first.ingest(120,0,sample)==1);check(first.ingest(120,0,sample)==0);
        check(first.coverage().records==1&&first.coverage().missing==1&&first.coverage().cursor==180);
        first.close();first.close();
        try{first.coverage();throw new AssertionError();}catch(IllegalStateException expected){}
        try(ActivitySession session=ActivitySession.open(dir,"fixture","test",true,120,240)){
            check(session.coverage().records==1);
            ExecutorService workers=Executors.newFixedThreadPool(4);
            try{List<Future<Long>> results=new ArrayList<>();for(int i=0;i<8;i++)results.add(workers.submit(()->session.ingest(180,null,sample)));
                long inserted=0;for(Future<Long> r:results)inserted+=r.get();check(inserted==1);
            }finally{workers.shutdown();check(workers.awaitTermination(5,TimeUnit.SECONDS));}
            check(session.coverage().cursor==240&&session.coverage().missing==0);
            try{session.ingest(180,null,new byte[8]);throw new AssertionError();}catch(ActivitySession.NativeFailure expected){check(expected.status==4);}
        }
        status(null,1);status(new byte[0],1);status(new byte[70001],1);status(new byte[]{(byte)255},1);
        status("{\"version\":2,\"command\":{\"op\":\"close\",\"handle\":1}}".getBytes(StandardCharsets.UTF_8),1);
        File invalid=new File(root,"missing");
        try{ActivitySession.open(invalid,"fixture","test",true,120,240);throw new AssertionError();}catch(IllegalArgumentException expected){}
        File database=new File(dir,"activity8.sqlite");check(database.delete());check(database.mkdir());
        try{ActivitySession.open(dir,"fixture","test",true,120,240);throw new AssertionError();}catch(ActivitySession.NativeFailure expected){check(expected.status==3);}
        check(database.delete());check(dir.delete());
        File workerDir=new File(root,"worker");check(workerDir.mkdir());
        ActivityWorker worker=ActivityWorker.start(workerDir,"fixture","worker",true,120,240);
        worker.ready().get(5,TimeUnit.SECONDS);
        check(worker.ingest(120,0,sample).get(5,TimeUnit.SECONDS)==1);
        ActivityWorker.Snapshot snap=worker.coverage().get(5,TimeUnit.SECONDS);
        check(snap.records==1&&snap.missing==1&&snap.cursor==180);
        worker.closeAsync().get(5,TimeUnit.SECONDS);
        ActivityWorker reopened=ActivityWorker.start(workerDir,"fixture","worker",true,120,240);
        reopened.ready().get(5,TimeUnit.SECONDS);
        check(reopened.coverage().get(5,TimeUnit.SECONDS).records==1);
        reopened.closeAsync().get(5,TimeUnit.SECONDS);
        check(new File(workerDir,"activity8.sqlite").delete());check(workerDir.delete());check(root.delete());
        System.out.println("PASS JVM -Xcheck:jni -> JNI -> C ABI -> Rust -> SQLite: reopen, UTF-8, lifecycle, concurrent replay, errors");
    }
}
