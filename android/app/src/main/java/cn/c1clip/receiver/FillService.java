package cn.c1clip.receiver;

import android.accessibilityservice.*;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Rect;
import android.view.accessibility.*;
import java.util.*;
import org.json.*;

public final class FillService extends AccessibilityService {
    private static FillService active;
    public static String status="准备就绪，点击猫眼开始";
    public static int completed=0,total=0;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String target="",batchId="",afterPkg="",signal="";
    private List<String[]> people=new ArrayList<>();private FillSession session;
    private long deadline,unknownSince,nextAction,hopAt;private int navTries,hopStage,scrolls,hopDelay,agreementAttempts;
    private boolean running,agreementClicked,backSent;
    private FlowRules.Step last=FlowRules.Step.UNKNOWN;
    @Override protected void onServiceConnected(){active=this;restrict(getPackageName());}
    private void restrict(String... packages){AccessibilityServiceInfo info=getServiceInfo();if(info!=null){info.packageNames=packages;setServiceInfo(info);}}
    public static boolean available(){return active!=null;}
    public static boolean isRunning(){return active!=null&&active.running;}
    public static boolean start(String pkg,List<String[]> persons,String batch,String after,int stayMs) throws Exception {
        if(active==null||persons.isEmpty())return false;
        FillService s=active;s.finish("正在准备");s.target=pkg;s.batchId=batch;s.afterPkg=after.equals(pkg)?"":after;s.hopDelay=stayMs;
        s.people=new ArrayList<>();Set<String> unique=new HashSet<>();for(String[] person:persons)if(unique.add(person[0]+"|"+person[1]))s.people.add(person.clone());
        JSONObject saved=Vault.read(s).optJSONObject("fillProgress");int index=0;boolean pending=false;
        if(saved!=null&&batch.equals(saved.optString("batch"))&&pkg.equals(saved.optString("target"))){index=saved.optInt("index");pending=saved.optBoolean("pending");}
        if(index<0||index>s.people.size())throw new Exception("进度不匹配，请接收新资料");
        s.session=new FillSession(index,pending);completed=index;total=s.people.size();s.running=true;s.hopStage=0;s.resetPerson();s.restrict(pkg);s.announce("正在准备 "+(index+1)+" / "+total+" 人");s.schedule(500);return true;
    }
    public static void cancel(String reason){if(active!=null&&active.running)active.finish(reason);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!running||hopStage!=0||event.getPackageName()==null||!target.contentEquals(event.getPackageName()))return;
        if(event.getEventType()==AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED){StringBuilder b=new StringBuilder();for(CharSequence t:event.getText())b.append(t);signal=b.toString();}
        // Poll quickly but never bypass the minimum interval after an action.
        if(SystemClock.elapsedRealtime()>=nextAction)schedule(80);
    }
    @Override public void onInterrupt(){finish("系统中断，已暂停");}
    @Override public void onDestroy(){finish("辅助填写已关闭");active=null;super.onDestroy();}
    private void schedule(long delay){handler.removeCallbacks(step);handler.postDelayed(step,delay);}
    private void announce(String text){status=text;sendBroadcast(new Intent(ReceiverService.UPDATE).setPackage(getPackageName()));}
    private void finish(String reason){running=false;handler.removeCallbacksAndMessages(null);target="";batchId="";afterPkg="";signal="";people.clear();restrict(getPackageName());announce(reason);}
    private void resetPerson(){deadline=SystemClock.elapsedRealtime()+90000;unknownSince=0;nextAction=0;navTries=0;scrolls=0;agreementClicked=false;agreementAttempts=0;backSent=false;signal="";last=FlowRules.Step.UNKNOWN;}
    private void checkpoint(boolean pending) throws Exception {synchronized(Vault.class){JSONObject state=Vault.read(this);state.put("fillProgress",new JSONObject().put("batch",batchId).put("target",target).put("index",session.index).put("pending",pending));Vault.write(this,state);}}
    private void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out){if(node==null||out.size()>=1200)return;out.add(node);for(int i=0;i<node.getChildCount()&&out.size()<1200;i++)collect(node.getChild(i),out);}
    private String text(AccessibilityNodeInfo n){return n.getText()==null?"":n.getText().toString();}
    private Set<String> words(List<AccessibilityNodeInfo> nodes){Set<String> result=new HashSet<>();for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()){result.add(FlowRules.norm(text(n)));if(n.getContentDescription()!=null)result.add(FlowRules.norm(n.getContentDescription().toString()));}return result;}
    private boolean click(AccessibilityNodeInfo node){for(int i=0;i<4&&node!=null;i++,node=node.getParent())if(node.isVisibleToUser()&&node.isEnabled()&&node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);return false;}
    private AccessibilityNodeInfo unique(List<AccessibilityNodeInfo> nodes,String... labels){Set<String> wanted=new HashSet<>(Arrays.asList(labels));Map<String,AccessibilityNodeInfo> found=new LinkedHashMap<>();for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&(wanted.contains(FlowRules.norm(text(n)))||wanted.contains(FlowRules.norm(String.valueOf(n.getContentDescription()))))){Rect r=new Rect();n.getBoundsInScreen(r);found.put(r.toShortString(),n);}return found.size()==1?found.values().iterator().next():null;}
    private void acted(int ms){nextAction=SystemClock.elapsedRealtime()+ms;schedule(ms);}
    private void waitUnknown(String reason){long now=SystemClock.elapsedRealtime();if(unknownSince==0)unknownSince=now;if(now-unknownSince>=10000)finish(reason);else schedule(300);}
    private boolean savedRow(List<AccessibilityNodeInfo> nodes,String name,String id){
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&FillSession.maskedIdMatches(text(n),id)){
            AccessibilityNodeInfo parent=n.getParent();for(int level=0;level<2&&parent!=null;level++,parent=parent.getParent()){
                List<AccessibilityNodeInfo> row=new ArrayList<>();collect(parent,row);for(String value:words(row))if(value.equals(name)||value.matches("[*•●]+"+java.util.regex.Pattern.quote(name.substring(name.length()-1))))return true;
            }
        }return false;
    }
    private boolean scroll(List<AccessibilityNodeInfo> nodes,boolean forward){for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&n.isScrollable()&&n.performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;return false;}
    private final Runnable step=()->tick();
    private void tick(){
        if(!running)return;long now=SystemClock.elapsedRealtime();
        if(getSystemService(KeyguardManager.class).isKeyguardLocked()||!getSystemService(PowerManager.class).isInteractive()){finish("手机已锁屏，进度已保留");return;}
        try{JSONObject latest=Vault.latest(this);if(latest==null||!batchId.equals(latest.optString("id"))){finish("资料已更新或过期，已停止");return;}}catch(Exception e){finish("读取资料失败，已停止");return;}
        if(hopStage!=0){hop(now);return;}
        if(session.index>=people.size()){allDone();return;}
        if(now>deadline){finish("当前人员处理超时，进度已保留，请检查猫眼页面");return;}
        if(now<nextAction){schedule(nextAction-now);return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null||!target.contentEquals(root.getPackageName()==null?"":root.getPackageName())){waitUnknown("请回到猫眼后再次点击猫眼继续");return;}
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(root,nodes);Set<String> visible=words(nodes);FlowRules.Step page=FlowRules.detect(visible);
        String[] person=people.get(session.index);String name=person[0],id=person[1];
        String feedback=signal+" "+String.join(" ",visible);signal="";
        if(FillSession.permanentError(feedback)){finish("猫眼提示资料、验证或频率问题，请处理后继续；已保留进度");return;}
        if(session.pending&&(feedback.contains("添加成功")||feedback.contains("保存成功")))session.successSignal=true;
        if(page==FlowRules.Step.LIST){
            if(savedRow(nodes,name,id)){try{session.saved();checkpoint(false);completed=session.index;resetPerson();announce("已确认保存 "+completed+" / "+total+" 人");schedule(180);}catch(Exception e){finish("进度保存失败，请核对已保存人员");}return;}
            if(session.pending){if(scrolls++<5&&scroll(nodes,true)){acted(400);return;}waitUnknown("提交结果无法确认，已暂停；请核对观演人列表，避免重复添加");return;}
        }
        if(page==FlowRules.Step.FORM){
            unknownSince=0;
            if(session.pending){
                if(session.successSignal&&!backSent){backSent=true;performGlobalAction(GLOBAL_ACTION_BACK);acted(450);return;}
                if(FillSession.transientError(feedback)){
                    session.failedExplicitly();try{checkpoint(false);}catch(Exception e){finish("进度保存失败");return;}
                    if(session.attempts>=FillSession.MAX_ATTEMPTS){finish("连续添加失败，已尝试 6 次；进度已保留");return;}
                    announce("添加失败，准备第 "+(session.attempts+1)+" 次尝试");acted(1200);return;
                }
                if(session.sentAt==0||now-session.sentAt>15000){finish("等待保存结果超时，已暂停；请核对是否已保存后继续");return;}
                schedule(220);return;
            }
            fillAndSubmit(nodes,name,id,now);return;
        }
        if(page==FlowRules.Step.UNKNOWN){waitUnknown("页面或弹窗无法识别，已暂停；请处理后继续");return;}
        if(session.pending){waitUnknown("请返回观演人列表确认上次保存结果");return;}unknownSince=0;
        if(page==last)navTries++;else{last=page;navTries=0;}
        if(navTries>=5){finish("页面按钮未响应，进度已保留");return;}
        AccessibilityNodeInfo button=switch(page){case HOME->unique(nodes,"我的");case PROFILE->unique(nodes,"观演人信息");case LIST->unique(nodes,"+添加/修改观演人信息","添加/修改观演人信息");default->null;};
        if(button==null&&page==FlowRules.Step.LIST&&scroll(nodes,false)){acted(400);return;}
        if(button!=null)click(button);announce("正在填写第 "+(session.index+1)+" / "+total+" 人");acted(350);
    }
    private void fillAndSubmit(List<AccessibilityNodeInfo> nodes,String name,String id,long now){
        AccessibilityNodeInfo nf=null,df=null;int nc=0,dc=0;
        for(AccessibilityNodeInfo f:nodes)if(f.isVisibleToUser()&&f.isEnabled()&&f.isEditable()){
            String hint=FlowRules.norm(String.valueOf(f.getHintText())),t=FlowRules.norm(text(f));
            if(hint.equals("请输入姓名")||t.equals("请输入姓名")||t.equals(name)){nf=f;nc++;}
            if(hint.equals("请输入证件号")||t.equals("请输入证件号")||t.equals(id)){df=f;dc++;}
        }
        if(nf==null||df==null||nc!=1||dc!=1||nf.equals(df)){finish("输入框无法唯一识别，请检查页面");return;}
        boolean correct=text(nf).equals(name)&&text(df).equals(id);
        if(!correct){
            if(!emptyOrExpected(nf,"请输入姓名",name)||!emptyOrExpected(df,"请输入证件号",id)){finish("表单已有其他人员内容，请核对后清空再继续");return;}
            Bundle a=new Bundle();a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name);Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,id);
            if(!nf.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a)||!df.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)){finish("填写未完成，请检查输入框");return;}
            acted(180);return;
        }
        AccessibilityNodeInfo agreement=unique(nodes,"我已阅读并同意","我已阅读并同意《实名说明》","我已阅读并同意实名说明");
        List<AccessibilityNodeInfo> checks=new ArrayList<>();Rect label=new Rect();if(agreement!=null)agreement.getBoundsInScreen(label);
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&n.isCheckable()&&!String.valueOf(n.getClassName()).contains("Switch")){
            Rect r=new Rect();n.getBoundsInScreen(r);if(agreement!=null&&Math.abs(r.centerY()-label.centerY())<Math.max(label.height(),r.height())&&r.left<=label.right)checks.add(n);
        }
        if(checks.size()==1){if(!checks.get(0).isChecked()){if(agreementAttempts++>=3){finish("实名说明状态未更新，已暂停");return;}if(!click(checks.get(0))){finish("无法勾选实名说明，请检查页面");return;}agreementClicked=true;acted(300);return;}}
        else if(!agreementClicked){if(agreement==null||!click(agreement)){finish("无法识别实名说明勾选项，请检查页面");return;}agreementClicked=true;acted(220);return;}
        if(!session.maySubmit(now)){if(session.attempts>=FillSession.MAX_ATTEMPTS)finish("重试次数已达上限，已暂停");else schedule(220);return;}
        AccessibilityNodeInfo confirm=unique(nodes,"确定");if(confirm==null||!confirm.isEnabled()){waitUnknown("确定按钮不可用，请核对姓名、证件号与实名说明");return;}
        try{checkpoint(true);}catch(Exception e){finish("无法保存提交进度，已暂停");return;}
        if(!click(confirm)){try{checkpoint(false);}catch(Exception ignored){}finish("确定按钮未响应，已暂停");return;}
        session.submitted(now);announce("正在保存第 "+(session.index+1)+" / "+total+" 人");acted(220);
    }
    private boolean emptyOrExpected(AccessibilityNodeInfo n,String hint,String expected){String s=text(n).trim();return s.isEmpty()||s.equals(hint)||s.equals(expected);}
    private boolean launch(String pkg){try{Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i==null)return false;startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}catch(Exception e){return false;}}
    private void allDone(){
        if(afterPkg.isEmpty()){finish("全部 "+total+" 人已保存");return;}
        restrict(target,afterPkg);if(!launch(afterPkg)){finish("全部资料已保存，但后续应用无法打开");return;}hopStage=1;hopAt=SystemClock.elapsedRealtime();announce("全部资料已保存，正在打开所选应用");schedule(250);
    }
    private void hop(long now){
        AccessibilityNodeInfo root=getRootInActiveWindow();String pkg=root==null?"":String.valueOf(root.getPackageName());
        if(hopStage==1){if(pkg.equals(afterPkg)){hopStage=2;hopAt=now;announce("已打开所选应用，即将返回猫眼");schedule(hopDelay);}else if(now-hopAt>6000)finish("资料已保存，系统未允许打开所选应用");else schedule(250);return;}
        if(hopStage==2){if(!pkg.equals(afterPkg)){finish("资料已保存；你已切换页面，自动返回已取消");return;}if(!launch(target)){finish("资料已保存，请手动返回猫眼");return;}hopStage=3;hopAt=now;schedule(250);return;}
        if(pkg.equals(target))finish("全部 "+total+" 人已保存，已返回猫眼");else if(now-hopAt>6000)finish("资料已保存，请手动返回猫眼");else schedule(250);
    }
}
