package org.workspace.band7;
import static org.workspace.band7.RadarSession.*;
public final class RadarFeedbackTest {
    private static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        RadarSession s=new RadarSession(new Transport(){public boolean read(long g){return true;}public void close(){}},v->{},Origin.SYNTHETIC,Mode.CONTINUOUS);
        s.start(0);check(RadarFeedback.from(s.snapshot()).state==RadarFeedback.State.CONNECTING);s.connected(s.generation(),0);s.tick(0);
        check(RadarFeedback.from(s.snapshot()).state==RadarFeedback.State.WAITING_RSSI);
        for(int i=0;i<12;i++){long now=i*1200;if(i>0)s.tick(now);s.read(s.generation(),i<6?-80:-60,0,now+1);if(i==0)check(!RadarFeedback.from(s.snapshot()).strengthUsable);}
        RadarFeedback f=RadarFeedback.from(s.snapshot());check(f.state==RadarFeedback.State.LIVE&&f.trend==Trend.WARMER&&f.bestId==2&&f.history.size()==2&&f.filteredDbm==-60);
        s.stop(Reason.BACKGROUND,14000);check(RadarFeedback.from(s.snapshot()).state==RadarFeedback.State.PAUSED&&!RadarFeedback.from(s.snapshot()).strengthUsable);
        RadarSession no=new RadarSession(new Transport(){public boolean read(long g){return true;}public void close(){}},v->{},Origin.SYNTHETIC,Mode.CONTINUOUS);
        no.start(0);no.connected(no.generation(),0);no.tick(0);no.tick(2500);RadarFeedback absent=RadarFeedback.from(no.snapshot());check(absent.rawDbm==null&&absent.filteredDbm==null&&absent.bestId==0&&!absent.strengthUsable);
        for(boolean dark:new boolean[]{false,true}){RadarVisualModel.Palette p=RadarVisualModel.palette(dark);check(RadarVisualModel.contrast(p.text,p.background)>=4.5);check(RadarVisualModel.contrast(p.text,p.surface)>=4.5);check(RadarVisualModel.contrast(p.muted,p.surface)>=4.5);check(RadarVisualModel.contrast(p.onAccent,p.accent)>=4.5);for(int width:new int[]{240,280,320,360,480})for(int height:new int[]{144,180,220}){float r=RadarVisualModel.radius(width,height);check(r>=0&&r+12<=width/2f&&r+12<=height/2f);for(float phase:new float[]{0,.2f,.6f,1})check(RadarVisualModel.pulseRadius(r,phase)<=r);}}
        System.out.println("PASS feedback synthetic: collecting/valid/absent/paused, warm trend, best window, no fake strength, light/dark contrast and small-view geometry");
    }
}
