package cn.c1clip.receiver;

import android.accessibilityservice.*;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.*;
import java.util.*;
import org.json.*;

public final class FillService extends AccessibilityService {
    private static FillService active;
    public static String status="未运行辅助填写";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String target="",name="",id="",batchId="";
    private long deadline=0,unknownSince=0; private int actions=0;
    private boolean running=false,verify=false;
    private FlowRules.Step last=FlowRules.Step.UNKNOWN;private int repeats=0;
    @Override protected void onServiceConnected(){active=this;restrict(getPackageName());}
    private void restrict(String pkg){AccessibilityServiceInfo info=getServiceInfo();if(info!=null){info.packageNames=new String[]{pkg};setServiceInfo(info);}}
    public static boolean available(){return active!=null;}
    public static boolean start(String pkg,String person,String number,String batch){
        if(active==null)return false;FillService s=active;s.finish("已准备新的填写任务");s.target=pkg;s.name=person;s.id=number;s.batchId=batch;s.deadline=SystemClock.elapsedRealtime()+60000;s.unknownSince=0;s.actions=0;s.repeats=0;s.last=FlowRules.Step.UNKNOWN;s.verify=false;s.running=true;s.restrict(pkg);s.announce("正在打开猫眼，准备填写当前所选人员");s.handler.postDelayed(s.step,1200);return true;
    }
    public static void cancel(String reason){if(active!=null)active.finish(reason);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){/* A single scheduled loop avoids duplicate taps from event bursts. */}
    @Override public void onInterrupt(){finish("系统中断，任务已停止");}
    @Override public void onDestroy(){finish("辅助填写已关闭");active=null;super.onDestroy();}
    private void announce(String text){status=text;sendBroadcast(new Intent(ReceiverService.UPDATE).setPackage(getPackageName()));}
    private void finish(String reason){running=false;handler.removeCallbacksAndMessages(null);target="";name="";id="";batchId="";restrict(getPackageName());announce(reason);}
    private void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out){if(node==null||out.size()>=1200)return;out.add(node);for(int i=0;i<node.getChildCount()&&out.size()<1200;i++)collect(node.getChild(i),out);}
    private boolean click(AccessibilityNodeInfo node){for(int i=0;i<4&&node!=null;i++,node=node.getParent())if(node.isVisibleToUser()&&node.isEnabled()&&node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);return false;}
    private boolean clickUnique(List<AccessibilityNodeInfo> nodes,String... labels){
        Set<String> wanted=new HashSet<>(Arrays.asList(labels));List<AccessibilityNodeInfo> found=new ArrayList<>();for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&(wanted.contains(FlowRules.norm(String.valueOf(n.getText())))||wanted.contains(FlowRules.norm(String.valueOf(n.getContentDescription())))))found.add(n);
        // Labels may be exposed twice on an ancestor and its child. Only identical bounds are deduplicated.
        Map<String,AccessibilityNodeInfo> unique=new LinkedHashMap<>();for(AccessibilityNodeInfo n:found){android.graphics.Rect r=new android.graphics.Rect();n.getBoundsInScreen(r);unique.put(r.toShortString(),n);}return unique.size()==1&&click(unique.values().iterator().next());
    }
    private final Runnable step=new Runnable(){@Override public void run(){
        if(!running)return;
        if(getSystemService(KeyguardManager.class).isKeyguardLocked()||!getSystemService(PowerManager.class).isInteractive()){finish("手机已锁屏，请解锁后重新开始辅助填写");return;}
        if(SystemClock.elapsedRealtime()>deadline||actions>=12){finish("页面操作超时，已停止；请检查猫眼当前页面");return;}
        try{JSONObject latest=Vault.latest(FillService.this);if(latest==null||!batchId.equals(latest.optString("id"))){finish("资料已更新或过期，任务已停止");return;}}catch(Exception e){finish("无法读取本机资料，任务已停止");return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||!target.contentEquals(root.getPackageName()==null?"":root.getPackageName())){waitUnknown("请保持所选猫眼应用在前台");return;}
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(root,nodes);Set<String> words=new HashSet<>();for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()){words.add(FlowRules.norm(String.valueOf(n.getText())));words.add(FlowRules.norm(String.valueOf(n.getContentDescription())));}
        FlowRules.Step page=FlowRules.detect(words);
        if(page==FlowRules.Step.UNKNOWN){waitUnknown("当前页面无法识别，已停止。请回到截图中的猫眼页面重试");return;}
        unknownSince=0;
        if(page==FlowRules.Step.FORM){fill(nodes);return;}
        if(verify){finish("页面发生变化，已停止，请核对填写结果");return;}
        if(page==last)repeats++;else{last=page;repeats=0;}
        if(repeats>=3){finish("按钮未响应或控件不匹配，已停止；请手动进入添加观演人页后重试");return;}
        boolean ok=switch(page){case HOME->clickUnique(nodes,"我的");case PROFILE->clickUnique(nodes,"观演人信息");case LIST->clickUnique(nodes,"+添加/修改观演人信息","添加/修改观演人信息");default->false;};
        actions++;announce(ok?"正在进入猫眼观演人填写页面":"等待页面控件就绪");handler.postDelayed(this,1200);
    }};
    private void waitUnknown(String reason){long now=SystemClock.elapsedRealtime();if(unknownSince==0)unknownSince=now;if(now-unknownSince>=10000){finish(reason);return;}handler.postDelayed(step,700);}
    private void fill(List<AccessibilityNodeInfo> nodes){
        List<AccessibilityNodeInfo> fields=new ArrayList<>();for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&n.isEnabled()&&n.isEditable())fields.add(n);
        AccessibilityNodeInfo nf=null,df=null;int nc=0,dc=0;
        for(AccessibilityNodeInfo f:fields){String hint=FlowRules.norm(String.valueOf(f.getHintText())),text=FlowRules.norm(String.valueOf(f.getText()));if(hint.equals("请输入姓名")||text.equals("请输入姓名")||text.equals(name)){nf=f;nc++;}if(hint.equals("请输入证件号")||text.equals("请输入证件号")||text.equals(id)){df=f;dc++;}}
        if(nf==null||df==null||nc!=1||dc!=1){finish("未能唯一识别姓名与证件号输入框，请手动填写并反馈页面截图");return;}
        if(nf.equals(df)){finish("输入框识别冲突，已停止");return;}
        if(verify){if(name.contentEquals(nf.getText()==null?"":nf.getText())&&id.contentEquals(df.getText()==null?"":df.getText()))finish("已填好，请核对姓名和证件号；协议与确定由你操作");else finish("未能确认填写结果，请手动核对");return;}
        // Do not overwrite an existing person's values without the user clearing the form.
        if(!emptyOrPlaceholder(nf,"请输入姓名",name)||!emptyOrPlaceholder(df,"请输入证件号",id)){finish("输入框已有其他内容，已停止。请清空后重新开始");return;}
        Bundle a=new Bundle();a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name);Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,id);
        if(!nf.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a)||!df.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)){finish("页面不支持直接填写，请检查已填内容后手动补充");return;}
        verify=true;handler.postDelayed(step,700);
    }
    private boolean emptyOrPlaceholder(AccessibilityNodeInfo n,String placeholder,String expected){String t=n.getText()==null?"":n.getText().toString().trim();return t.isEmpty()||t.equals(placeholder)||t.equals(expected);}
}
