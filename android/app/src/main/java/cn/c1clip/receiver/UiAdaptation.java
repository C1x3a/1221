package cn.c1clip.receiver;

import android.content.*;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

/**
 * Per-device UI adaptation cache.
 * Stores geometry only; never stores page text, names, IDs or other personal data.
 */
public final class UiAdaptation {
    private static final String PREF="ui_adaptation_v1";
    private static final String ADD="add";
    private static final String AGREEMENT="agreement";

    private static String prefix(Context context,FlowRules.Platform platform,String role){
        DisplayMetrics dm=context.getResources().getDisplayMetrics();
        return Build.MANUFACTURER+"|"+Build.MODEL+"|sdk"+Build.VERSION.SDK_INT+"|"+dm.widthPixels+"x"+dm.heightPixels+"|"+platform.name()+"|"+role;
    }
    private static android.content.SharedPreferences prefs(Context context){return context.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}

    private static void remember(Context context,FlowRules.Platform platform,String role,Rect r){
        if(r==null||r.isEmpty())return;
        DisplayMetrics dm=context.getResources().getDisplayMetrics();if(dm.widthPixels<=0||dm.heightPixels<=0)return;
        String value=String.format(java.util.Locale.ROOT,"%.6f,%.6f,%.6f,%.6f",
                r.centerX()/(float)dm.widthPixels,r.centerY()/(float)dm.heightPixels,
                r.width()/(float)dm.widthPixels,r.height()/(float)dm.heightPixels);
        prefs(context).edit().putString(prefix(context,platform,role),value).apply();
    }

    public static void rememberAdd(Context context,FlowRules.Platform platform,AccessibilityNodeInfo node){
        if(node==null)return;Rect r=new Rect();node.getBoundsInScreen(r);remember(context,platform,ADD,r);
    }
    public static void rememberAgreement(Context context,FlowRules.Platform platform,AccessibilityNodeInfo node){
        if(node==null)return;Rect r=new Rect();node.getBoundsInScreen(r);remember(context,platform,AGREEMENT,r);
    }

    private static float[] learned(Context context,FlowRules.Platform platform,String role){
        String raw=prefs(context).getString(prefix(context,platform,role),"");if(raw.isEmpty())return null;
        try{String[] p=raw.split(",");if(p.length!=4)return null;return new float[]{Float.parseFloat(p[0]),Float.parseFloat(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3])};}catch(Exception e){return null;}
    }

    public static AccessibilityNodeInfo findAdd(Context context,FlowRules.Platform platform,List<AccessibilityNodeInfo> nodes){
        float[] f=learned(context,platform,ADD);if(f==null)return null;
        DisplayMetrics dm=context.getResources().getDisplayMetrics();long best=Long.MAX_VALUE;AccessibilityNodeInfo result=null;
        int ex=Math.round(f[0]*dm.widthPixels),ey=Math.round(f[1]*dm.heightPixels),ew=Math.max(1,Math.round(f[2]*dm.widthPixels)),eh=Math.max(1,Math.round(f[3]*dm.heightPixels));
        for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&node.isEnabled()){
            Rect r=new Rect();node.getBoundsInScreen(r);if(r.isEmpty()||r.width()<dm.widthPixels*30/100||r.height()<dp(context,28)||r.height()>dm.heightPixels*25/100)continue;
            long dx=r.centerX()-ex,dy=r.centerY()-ey,dw=r.width()-ew,dh=r.height()-eh;
            long score=dx*dx+dy*dy+Math.abs(dw)*30L+Math.abs(dh)*50L-(node.isClickable()?250000L:0);
            if(score<best){best=score;result=node;}
        }
        return result;
    }

    public static AccessibilityNodeInfo findAgreement(Context context,FlowRules.Platform platform,List<AccessibilityNodeInfo> nodes,Rect label){
        float[] f=learned(context,platform,AGREEMENT);if(f==null)return null;
        DisplayMetrics dm=context.getResources().getDisplayMetrics();long best=Long.MAX_VALUE;AccessibilityNodeInfo result=null;
        int ex=Math.round(f[0]*dm.widthPixels),ey=Math.round(f[1]*dm.heightPixels);
        int tolerance=Math.max(dp(context,72),dm.heightPixels*7/100);
        for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&node.isEnabled()){
            Rect r=new Rect();node.getBoundsInScreen(r);if(r.isEmpty()||r.width()>dm.widthPixels*35/100||r.height()>dm.heightPixels*12/100)continue;
            if(label!=null&&!label.isEmpty()&&Math.abs(r.centerY()-label.centerY())>tolerance)continue;
            long dx=r.centerX()-ex,dy=r.centerY()-ey;
            long score=dx*dx+dy*dy-(node.isCheckable()?600000L:0)-(node.isClickable()?250000L:0);
            if(score<best){best=score;result=node;}
        }
        return result;
    }

    public static void clear(Context context){prefs(context).edit().clear().apply();}
    public static String summary(Context context){
        DisplayMetrics dm=context.getResources().getDisplayMetrics();
        boolean ma=learned(context,FlowRules.Platform.MAOYAN,ADD)!=null;
        boolean pa=learned(context,FlowRules.Platform.PIAOXINGQIU,ADD)!=null;
        return Build.MANUFACTURER+" "+Build.MODEL+" · Android "+Build.VERSION.RELEASE+" · "+dm.widthPixels+"×"+dm.heightPixels+
                "\n猫眼："+(ma?"已学习":"待自动学习")+" · 票星球："+(pa?"已学习":"待自动学习");
    }
}
