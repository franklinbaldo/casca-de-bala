// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
/** Shared radar geometry/palette for Android Canvas and host geometry/PNG checks.
 * Circles encode receipt of data and relative strength; there is no target angle/point.
 */
public final class RadarVisualModel {
    private RadarVisualModel(){}
    public static final class Palette {
        public final int background,text,muted,surface,track,accent,onAccent,warning;
        Palette(int bg,int text,int muted,int surface,int track,int accent,int onAccent,int warning){background=bg;this.text=text;this.muted=muted;this.surface=surface;this.track=track;this.accent=accent;this.onAccent=onAccent;this.warning=warning;}
    }
    public static Palette palette(boolean dark){return dark?
        new Palette(0xff101a22,0xfff1f7ff,0xffb9c9dc,0xff1a2b3a,0xff53677c,0xff65d8e6,0xff052c34,0xffffce75):
        new Palette(0xfff5f8fc,0xff10283e,0xff425c75,0xffffffff,0xff879bae,0xff006779,0xffffffff,0xff845200);}
    public static float radius(float width,float height){return Math.max(0,Math.min(width,height)/2-12);}
    public static float pulseRadius(float radius,float phase){return radius*(.25f+.75f*Math.max(0,Math.min(1,phase)));}
    private static double luminance(int color){double[] c={((color>>16)&255)/255.0,((color>>8)&255)/255.0,(color&255)/255.0};double result=0;double[] w={.2126,.7152,.0722};for(int i=0;i<3;i++)result+=w[i]*(c[i]<=.04045?c[i]/12.92:Math.pow((c[i]+.055)/1.055,2.4));return result;}
    public static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return(Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
}
