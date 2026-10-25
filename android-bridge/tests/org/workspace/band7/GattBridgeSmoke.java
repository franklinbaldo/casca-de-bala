package org.workspace.band7;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Synthetic envelopes only: real JNI/Rust, no Android stack or Bluetooth. */
public final class GattBridgeSmoke {
    private static void check(boolean condition){if(!condition)throw new AssertionError();}
    private static String call(String json){return new String(GattBasics.process(json.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8);}
    private static String inspect(long id,String kind,String outcome){return call("{\"version\":1,\"id\":"+id+",\"command\":{\"op\":\"interpret\",\"kind\":\""+kind+"\",\"reader\":\"synthetic\",\"outcome\":"+outcome+"}}");}
    public static void main(String[] args)throws Exception{
        check(inspect(42,"battery","{\"state\":\"gatt\",\"status\":0,\"bytes\":[91]}").contains("\"battery_percent\":91"));
        check(inspect(43,"software_revision","{\"state\":\"gatt\",\"status\":0,\"bytes\":[195,167]}").contains("ç"));
        check(inspect(44,"pnp_id","{\"state\":\"gatt\",\"status\":0,\"bytes\":[1,87,1,103,0,2,1]}").contains("\"product_id\":103"));
        check(inspect(45,"battery","{\"state\":\"not_attempted\"}").contains("not_read"));
        check(inspect(46,"battery","{\"state\":\"permission_denied\"}").contains("permission_denied"));
        check(inspect(47,"battery","{\"state\":\"gatt\",\"status\":5,\"bytes\":[91]}").contains("\"gatt_failure\":5"));
        check(inspect(48,"battery","{\"state\":\"gatt\",\"status\":0,\"bytes\":[101]}").contains("percent"));
        String plan=call("{\"version\":1,\"id\":49,\"command\":{\"op\":\"plan\",\"connect_permission\":true,\"discovery\":[{\"service\":{\"bluetooth16\":6162},\"characteristic\":{\"bluetooth16\":10827},\"readable\":true}]}}");
        check(plan.contains("privileged_hid"));check(plan.contains("\"id\":49"));
        check(call("{}").contains("invalid_input"));
        check(new String(GattBasics.process(new byte[]{(byte)255}),StandardCharsets.UTF_8).contains("invalid_input"));
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[32769]}){
            try{GattBasics.process(invalid);throw new AssertionError();}catch(IllegalArgumentException expected){}
            check(NativeBridge.gatt(invalid)==null);
        }
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try{
            java.util.List<Future<String>> results=new java.util.ArrayList<>();
            for(int i=0;i<32;i++){final int id=i;results.add(pool.submit(()->inspect(id,"battery","{\"state\":\"gatt\",\"status\":0,\"bytes\":[91]}")));}
            for(int i=0;i<32;i++)check(results.get(i).get(5,TimeUnit.SECONDS).contains("\"id\":"+i+","));
        }finally{pool.shutdown();check(pool.awaitTermination(5,TimeUnit.SECONDS));}
        System.out.println("PASS synthetic GATT envelopes -> real JNI -> Rust: provenance, UTF-8, errors, IDs, concurrency; no BLE");
    }
}
