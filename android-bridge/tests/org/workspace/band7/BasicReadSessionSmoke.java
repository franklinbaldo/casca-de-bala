package org.workspace.band7;
import java.util.*;
import static org.workspace.band7.BasicReadSession.*;

public final class BasicReadSessionSmoke {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    private static final class Fake implements Transport,BasicReadSession.Observer {
        int calls,closes;String reason;boolean accepted=true,denied;final List<String> results=new ArrayList<>();
        @Override public boolean read(long generation,Key key){calls++;if(denied)throw new SecurityException();return accepted;}
        @Override public void close(){closes++;}
        @Override public void result(Key key,String json){results.add(json);}
        @Override public void stopped(String value){reason=value;}
        BasicReadSession session(){return new BasicReadSession(this,this,Origin.SYNTHETIC);}
    }
    public static void main(String[] args){
        Fake f=new Fake();BasicReadSession s=f.session();s.start(Arrays.asList(Key.BATTERY,Key.PNP),true,0);
        check(f.calls==1);s.read(s.generation()+1,Key.BATTERY,0,new byte[]{91},1);check(f.results.isEmpty());
        s.read(s.generation(),Key.SOFTWARE,0,new byte[]{91},1);check(f.results.isEmpty());
        s.read(s.generation(),Key.BATTERY,0,new byte[]{91},1);check(f.calls==2&&f.results.get(0).contains("\"battery_percent\":91"));
        check(f.results.get(0).contains("synthetic"));
        s.read(s.generation(),Key.BATTERY,0,new byte[]{91},2);check(f.results.size()==1);
        s.read(s.generation(),Key.PNP,0,new byte[]{1,87,1,103,0,2,1},2);check(f.closes==1&&f.results.size()==2);
        s.close();check(f.closes==1);check(f.reason.equals("plan_finished_not_full_collection"));
        for(int mode=0;mode<5;mode++){
            Fake bad=new Fake();bad.denied=mode==1;bad.accepted=mode!=2;BasicReadSession b=bad.session();
            b.start(Arrays.asList(Key.BATTERY),mode!=0,100);
            if(mode==3)b.tick(10100);
            if(mode==4)b.read(b.generation(),Key.BATTERY,5,new byte[0],101);
            check(bad.closes==1);
            if(mode==0)check(bad.calls==0);
            if(mode==1)check(bad.results.get(0).contains("permission_denied"));
            if(mode==2)check(bad.results.get(0).contains("not_read"));
            if(mode==3)check(bad.results.isEmpty()&&bad.reason.equals("timeout"));
            if(mode==4)check(bad.results.get(0).contains("gatt_failure"));
            b.read(b.generation(),Key.BATTERY,0,new byte[]{91},10200);check(bad.closes==1);
        }
        Fake old=new Fake();BasicReadSession a=old.session();a.start(Arrays.asList(Key.BATTERY),true,0);a.close();
        BasicReadSession newer=old.session();newer.start(Arrays.asList(Key.BATTERY),true,0);
        newer.read(a.generation(),Key.BATTERY,0,new byte[]{91},1);check(old.results.isEmpty());newer.close();
        check(Key.from(Key.sig(0x180f),Key.sig(0x2a19))==Key.BATTERY);
        check(Key.from(Key.sig(0x1812),Key.sig(0x2a4b))==null);
        check(Key.from(Key.sig(0x180f),UUID.fromString("00002a19-0000-3512-2118-0009af100700"))==null);
        Fake limit=new Fake();try{limit.session().start(Arrays.asList(Key.BATTERY,Key.BATTERY),true,0);throw new AssertionError();}catch(IllegalArgumentException expected){}
        BasicReadSession invalid=limit.session();invalid.start(Arrays.asList(Key.BATTERY),true,0);
        invalid.read(invalid.generation(),Key.BATTERY,0,new byte[65],1);check(limit.reason.equals("invalid_callback"));
        System.out.println("PASS synthetic transport controller -> real JNI decoders: serial, stale callbacks, timeout, lifecycle, permission, limits");
    }
}
