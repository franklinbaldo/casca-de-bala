package org.workspace.band7.app;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import org.workspace.band7.RadarFeedback;
import org.workspace.band7.RadarVisualModel;

/** Receipt animation only. No target coordinate, bearing, distance or sensor access. */
final class RadarPulseView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final RadarVisualModel.Palette colors;
    private RadarFeedback feedback;private long generation=-1,sampleAt=-1,pulseStart=-1;
    RadarPulseView(Context context,RadarVisualModel.Palette colors){super(context);this.colors=colors;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void submit(RadarFeedback value){
        feedback=value;
        if(value.fresh&&value.sampleAtMs>=0&&(generation!=value.generation||sampleAt!=value.sampleAtMs))pulseStart=SystemClock.uptimeMillis();
        if(!value.fresh||!value.active)pulseStart=-1;
        generation=value.generation;sampleAt=value.sampleAtMs;invalidate();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float density=getResources().getDisplayMetrics().density;
        float cx=getWidth()/2f,cy=getHeight()/2f,r=RadarVisualModel.radius(getWidth()/density,getHeight()/density)*density;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*density);paint.setColor(colors.track);paint.setAlpha(150);
        for(int i=1;i<=4;i++)canvas.drawCircle(cx,cy,r*i/4,paint);
        if(feedback!=null&&feedback.strengthUsable){paint.setColor(colors.accent);paint.setAlpha(255);paint.setStrokeWidth((2+4*(float)feedback.visualStrength())*density);canvas.drawCircle(cx,cy,r,paint);}
        if(pulseStart>=0&&feedback!=null&&feedback.fresh&&isShown()){
            float phase=(SystemClock.uptimeMillis()-pulseStart)/700f;
            if(phase<1){paint.setColor(colors.accent);paint.setStrokeWidth(4*density);paint.setAlpha((int)(200*(1-phase)));canvas.drawCircle(cx,cy,RadarVisualModel.pulseRadius(r/density,phase)*density,paint);postInvalidateOnAnimation();}else pulseStart=-1;
        }
        paint.setAlpha(255);
    }
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)pulseStart=-1;}
    @Override protected void onDetachedFromWindow(){pulseStart=-1;super.onDetachedFromWindow();}
}
