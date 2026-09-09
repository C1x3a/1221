package cn.c1clip.receiver;

import android.accessibilityservice.*;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Path;
import android.view.*;
import android.widget.*;
import android.view.accessibility.*;
import java.util.*;
import org.json.*;

public final class FillService extends AccessibilityService {
    private static FillService active;
    public static String status="准备就绪，选择填写平台开始";
    public static int completed=0,total=0;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String target="",batchId="",afterPkg="",configuredPkg="",signal="",maoyanPkg="",planetPkg="";
    private FlowRules.Platform platform=FlowRules.Platform.MAOYAN;
    private List<String[]> people=new ArrayList<>();private FillSession session;
    private long deadline,unknownSince,nextAction,hopAt,pageSince,lastNavAt;private int hopStage,hopDelay,agreementAttempts,navAttempts,unknownPhase,listScanPhase,listScanMoves,nameActivationAttempts,idActivationAttempts;
    private boolean running,paused,agreementClicked,backSent,verificationBack;
    private FlowRules.Step last=FlowRules.Step.UNKNOWN;
    private FlowRules.Step lastSeen=FlowRules.Step.UNKNOWN;
    private String lastNavKey="";
    private WindowManager windowManager;private View overlay;private TextView overlayTitle,overlayStatus;private Button overlayToggle,overlayMinimize;private ProgressBar overlayProgress;private LinearLayout overlayDetails;private WindowManager.LayoutParams overlayParams;private boolean overlayMinimized;
    @Override protected void onServiceConnected(){active=this;restrict(getPackageName());}
    private void restrict(String... packages){AccessibilityServiceInfo info=getServiceInfo();if(info!=null){info.packageNames=packages;setServiceInfo(info);}}
    public static boolean available(){return active!=null;}
    public static boolean isRunning(){return active!=null&&active.running;}
    public static boolean start(String pkg,FlowRules.Platform platform,List<String[]> persons,String batch,String after,int stayMs,String maoyan,String planet,String configured) throws Exception {
        if(active==null||persons.isEmpty())return false;
        FillService s=active;s.finish("正在准备");s.target=pkg;s.platform=platform;s.batchId=batch;s.afterPkg=after.equals(pkg)?"":after;s.configuredPkg=configured;s.hopDelay=stayMs;s.maoyanPkg=maoyan;s.planetPkg=planet;
        s.people=new ArrayList<>();Set<String> unique=new HashSet<>();for(String[] person:persons)if(unique.add(person[0]+"|"+person[1]))s.people.add(person.clone());
        JSONObject saved=Vault.read(s).optJSONObject(s.progressKey());int index=0;boolean pending=false;
        if(saved!=null&&batch.equals(saved.optString("batch"))&&pkg.equals(saved.optString("target"))&&platform.name().equals(saved.optString("platform",FlowRules.Platform.MAOYAN.name()))){index=saved.optInt("index");pending=saved.optBoolean("pending");}
        if(index<0||index>s.people.size())throw new Exception("进度不匹配，请接收新资料");
        // A newly started run must verify a previously completed list again. This catches people deleted in the ticket app.
        if(index==s.people.size()){index=0;pending=false;}
        s.session=new FillSession(index,pending);completed=index;total=s.people.size();s.running=true;s.paused=false;s.hopStage=0;s.resetPerson();s.restrict(pkg);s.showOverlay();s.announce("正在准备 "+(index+1)+" / "+total+" 人");s.schedule(500);return true;
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
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void announce(String text){status=text;updateOverlay();sendBroadcast(new Intent(ReceiverService.UPDATE).setPackage(getPackageName()));}
    private void finish(String reason){running=false;paused=false;handler.removeCallbacksAndMessages(null);removeOverlay();target="";batchId="";afterPkg="";configuredPkg="";signal="";people.clear();restrict(getPackageName());announce(reason);}
    private void resetPerson(){deadline=SystemClock.elapsedRealtime()+180000;unknownSince=0;nextAction=0;pageSince=0;lastNavAt=0;navAttempts=0;unknownPhase=0;listScanPhase=0;listScanMoves=0;nameActivationAttempts=0;idActivationAttempts=0;lastNavKey="";agreementClicked=false;agreementAttempts=0;backSent=false;verificationBack=false;signal="";last=FlowRules.Step.UNKNOWN;lastSeen=FlowRules.Step.UNKNOWN;}
    private String progressKey(){return platform==FlowRules.Platform.PIAOXINGQIU?"fillProgress_PIAOXINGQIU":"fillProgress_MAOYAN";}
    private void checkpoint(boolean pending) throws Exception {synchronized(Vault.class){JSONObject state=Vault.read(this);state.put(progressKey(),new JSONObject().put("batch",batchId).put("target",target).put("platform",platform.name()).put("index",session.index).put("pending",pending));Vault.write(this,state);}}
    private void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out){if(node==null||out.size()>=1200)return;out.add(node);for(int i=0;i<node.getChildCount()&&out.size()<1200;i++)collect(node.getChild(i),out);}
    private String text(AccessibilityNodeInfo n){return n.getText()==null?"":n.getText().toString();}
    private Set<String> words(List<AccessibilityNodeInfo> nodes){Set<String> result=new HashSet<>();for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()){result.add(FlowRules.norm(text(n)));if(n.getContentDescription()!=null)result.add(FlowRules.norm(n.getContentDescription().toString()));}return result;}
    private boolean click(AccessibilityNodeInfo node){for(int i=0;i<4&&node!=null;i++,node=node.getParent())if(node.isVisibleToUser()&&node.isEnabled()&&node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);return false;}
    private AccessibilityNodeInfo unique(List<AccessibilityNodeInfo> nodes,String... labels){Set<String> wanted=new HashSet<>(Arrays.asList(labels));Map<String,AccessibilityNodeInfo> found=new LinkedHashMap<>();for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&(wanted.contains(FlowRules.norm(text(n)))||wanted.contains(FlowRules.norm(String.valueOf(n.getContentDescription()))))){Rect r=new Rect();n.getBoundsInScreen(r);found.put(r.toShortString(),n);}return found.size()==1?found.values().iterator().next():null;}
    private AccessibilityNodeInfo best(List<AccessibilityNodeInfo> nodes,String label){
        String wanted=FlowRules.norm(label);AccessibilityNodeInfo result=null;long score=Long.MIN_VALUE;
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()){
            String a=FlowRules.norm(text(n)),b=FlowRules.norm(String.valueOf(n.getContentDescription()));if(!a.contains(wanted)&&!b.contains(wanted))continue;
            Rect r=new Rect();n.getBoundsInScreen(r);long next=(a.equals(wanted)||b.equals(wanted)?1000000:0)+(n.isClickable()?500000:0)+(long)r.width()*r.height()+r.centerY();
            if(next>score){score=next;result=n;}
        }return result;
    }
    private boolean tap(Rect bounds){if(bounds.isEmpty())return false;Path path=new Path();path.moveTo(bounds.centerX(),bounds.centerY());return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,60)).build(),null,null);}
    private boolean clickOrTap(AccessibilityNodeInfo node){if(node==null)return false;if(click(node))return true;Rect r=new Rect();node.getBoundsInScreen(r);return tap(r);}
    private void acted(int ms){nextAction=SystemClock.elapsedRealtime()+ms;schedule(ms);}
    private boolean stable(FlowRules.Step page,long now){if(page!=lastSeen){lastSeen=page;pageSince=now;lastNavKey="";navAttempts=0;return false;}return now-pageSince>=420;}
    private boolean navigationReady(String key,long now){
        if(!key.equals(lastNavKey)){lastNavKey=key;lastNavAt=0;navAttempts=0;}
        if(lastNavAt!=0&&now-lastNavAt<2200){schedule(Math.max(150,2200-(now-lastNavAt)));return false;}
        if(navAttempts>=3){announce("页面没有响应，正在重新打开"+FlowRules.platformName(platform));launch(target);lastNavAt=now;navAttempts=0;acted(3500);return false;}
        lastNavAt=now;navAttempts++;return true;
    }
    private void waitUnknown(String reason){
        long now=SystemClock.elapsedRealtime();
        if(unknownSince==0){unknownSince=now;unknownPhase=0;announce(reason+"，等待页面稳定");schedule(600);return;}
        long waited=now-unknownSince;
        if(unknownPhase==0&&waited>=3500){unknownPhase=1;launch(target);announce("未识别当前页面，已重新打开"+FlowRules.platformName(platform)+"一次");acted(4500);return;}
        if(unknownPhase==1&&waited>=10000){unknownPhase=2;paused=true;announce("无法安全识别当前页面，已暂停；请手动进入首页或人员页后点继续");schedule(800);return;}
        schedule(500);
    }
    private void recover(String reason){announce(reason+"，等待当前页面重新识别");lastSeen=FlowRules.Step.UNKNOWN;pageSince=0;acted(900);}

    private Button overlayButton(String text,Runnable run){Button b=new Button(this);b.setText(text);b.setTextSize(9);b.setAllCaps(false);b.setMinHeight(dp(30));b.setPadding(dp(2),0,dp(2),0);b.setOnClickListener(v->run.run());return b;}
    private Button overlayMiniButton(String text,Runnable run){Button b=new Button(this);b.setText(text);b.setTextSize(10);b.setAllCaps(false);b.setMinWidth(0);b.setMinHeight(0);b.setPadding(0,0,0,0);b.setOnClickListener(v->run.run());return b;}
    private void showOverlay(){
        removeOverlay();windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(7),dp(10),dp(7));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xeeffffff);bg.setCornerRadius(dp(14));bg.setStroke(dp(1),Color.rgb(210,222,242));box.setBackground(bg);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);overlayTitle=new TextView(this);overlayTitle.setTextColor(Color.rgb(17,36,65));overlayTitle.setTextSize(11);overlayTitle.setTypeface(null,1);overlayTitle.setGravity(Gravity.CENTER);top.addView(overlayTitle,new LinearLayout.LayoutParams(0,dp(25),1));overlayMinimize=overlayMiniButton("－",this::toggleOverlaySize);top.addView(overlayMinimize,new LinearLayout.LayoutParams(dp(24),dp(24)));top.addView(overlayMiniButton("×",this::closeOverlayOnly),new LinearLayout.LayoutParams(dp(24),dp(24)));box.addView(top,new LinearLayout.LayoutParams(-1,dp(25)));
        overlayDetails=new LinearLayout(this);overlayDetails.setOrientation(LinearLayout.VERTICAL);overlayStatus=new TextView(this);overlayStatus.setTextColor(Color.rgb(80,99,125));overlayStatus.setTextSize(9);overlayStatus.setGravity(Gravity.CENTER);overlayStatus.setMaxLines(2);overlayDetails.addView(overlayStatus,new LinearLayout.LayoutParams(-1,0,1));overlayProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);overlayProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(31,100,250)));overlayDetails.addView(overlayProgress,new LinearLayout.LayoutParams(-1,dp(4)));LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);overlayToggle=overlayButton("暂停",this::togglePause);controls.addView(overlayToggle,new LinearLayout.LayoutParams(0,dp(32),1));controls.addView(overlayButton("切换应用",this::openConfiguredApp),new LinearLayout.LayoutParams(0,dp(32),1));overlayDetails.addView(controls,new LinearLayout.LayoutParams(-1,dp(32)));box.addView(overlayDetails,new LinearLayout.LayoutParams(-1,0,1));
        android.content.SharedPreferences pos=getSharedPreferences("overlay_position",MODE_PRIVATE);overlayParams=new WindowManager.LayoutParams(dp(148),dp(148),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);overlayParams.gravity=Gravity.TOP|Gravity.START;overlayParams.x=pos.getInt("x",dp(12));overlayParams.y=pos.getInt("y",dp(72));
        final float[] down=new float[4];box.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){down[0]=e.getRawX();down[1]=e.getRawY();down[2]=overlayParams.x;down[3]=overlayParams.y;return true;}if(e.getAction()==MotionEvent.ACTION_MOVE){overlayParams.x=(int)(down[2]+e.getRawX()-down[0]);overlayParams.y=(int)(down[3]+e.getRawY()-down[1]);try{windowManager.updateViewLayout(overlay,overlayParams);}catch(Exception ignored){}return true;}if(e.getAction()==MotionEvent.ACTION_UP){pos.edit().putInt("x",overlayParams.x).putInt("y",overlayParams.y).apply();return true;}return false;});
        overlay=box;try{windowManager.addView(overlay,overlayParams);}catch(Exception e){overlay=null;}updateOverlay();
    }
    private void closeOverlayOnly(){if(!running)return;paused=true;try{checkpoint(session.pending);}catch(Exception ignored){}announce("悬浮窗已关闭，任务已暂停且进度已保留");removeOverlay();Toast.makeText(this,"悬浮窗已关闭，填写任务已暂停",Toast.LENGTH_SHORT).show();}
    private void toggleOverlaySize(){if(overlay==null)return;overlayMinimized=!overlayMinimized;overlayDetails.setVisibility(overlayMinimized?View.GONE:View.VISIBLE);overlayMinimize.setText(overlayMinimized?"□":"－");overlayParams.width=dp(overlayMinimized?76:148);overlayParams.height=dp(overlayMinimized?52:148);try{windowManager.updateViewLayout(overlay,overlayParams);}catch(Exception ignored){}updateOverlay();}
    private void togglePause(){paused=!paused;if(paused)announce("已暂停，当前步骤已保留");else{unknownSince=0;pageSince=0;announce("继续识别当前页面");schedule(100);}}
    private void updateOverlay(){if(overlayTitle!=null)overlayTitle.setText(overlayMinimized?FlowRules.platformName(platform).substring(0,1)+" "+Math.min(total,completed+1)+"/"+total:FlowRules.platformName(platform)+"  "+Math.min(total,completed+1)+" / "+total);if(overlayStatus!=null)overlayStatus.setText(status);if(overlayToggle!=null)overlayToggle.setText(paused?"继续":"暂停");if(overlayProgress!=null){overlayProgress.setMax(Math.max(1,total));overlayProgress.setProgress(Math.min(total,completed+1));}}
    private void removeOverlay(){if(overlay!=null&&windowManager!=null)try{windowManager.removeView(overlay);}catch(Exception ignored){}overlay=null;overlayTitle=null;overlayStatus=null;overlayToggle=null;overlayMinimize=null;overlayProgress=null;overlayDetails=null;overlayMinimized=false;}
    private void openConfiguredApp(){
        if(!running)return;if(configuredPkg==null||configuredPkg.isEmpty()){announce("尚未设置要切换的软件，请回接收端设置");return;}if(configuredPkg.equals(target)){announce("设置的软件就是当前填写平台");return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();String activePkg=root==null||root.getPackageName()==null?"":root.getPackageName().toString();String destination=activePkg.equals(target)?configuredPkg:target;String destinationName=destination.equals(target)?FlowRules.platformName(platform):"设置的软件";
        try{checkpoint(session.pending);restrict(target,configuredPkg);if(!launch(destination)){announce("无法打开"+destinationName+"，填写进度仍保留");return;}announce("正在切换到"+destinationName+"，填写进度已保留");schedule(700);}catch(Exception e){announce("切换应用失败，填写任务仍保留");}
    }
    private boolean savedRow(List<AccessibilityNodeInfo> nodes,String name,String id){
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&FillSession.maskedIdMatches(text(n),id)){
            AccessibilityNodeInfo parent=n.getParent();for(int level=0;level<2&&parent!=null;level++,parent=parent.getParent()){
                List<AccessibilityNodeInfo> row=new ArrayList<>();collect(parent,row);for(String value:words(row))if(maskedNameMatches(value,name))return true;
            }
        }return false;
    }
    private boolean maskedNameMatches(String shown,String name){String s=FlowRules.norm(shown);if(s.equals(name))return true;String visible=s.replaceAll("^[*•●]+","");return visible.length()>0&&visible.length()<name.length()&&name.endsWith(visible)&&s.matches("[*•●]+.*");}
    private boolean scroll(List<AccessibilityNodeInfo> nodes,boolean forward){for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&n.isScrollable()&&n.performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;return false;}
    private final Runnable step=()->tick();
    private void tick(){
        if(!running)return;long now=SystemClock.elapsedRealtime();
        if(paused){schedule(700);return;}
        if(getSystemService(KeyguardManager.class).isKeyguardLocked()||!getSystemService(PowerManager.class).isInteractive()){finish("手机已锁屏，进度已保留");return;}
        try{JSONObject latest=Vault.latest(this);if(latest==null||!batchId.equals(latest.optString("id"))){finish("资料已更新或过期，已停止");return;}}catch(Exception e){finish("读取资料失败，已停止");return;}
        if(hopStage!=0){hop(now);return;}
        if(session.index>=people.size()){allDone();return;}
        String app=FlowRules.platformName(platform);
        if(now<nextAction){schedule(nextAction-now);return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();String activePkg=root==null||root.getPackageName()==null?"":root.getPackageName().toString();
        if(activePkg.equals(getPackageName())){paused=true;announce("已回到接收端，任务已暂停；点继续或平台按钮恢复");schedule(700);return;}
        if(root==null||!target.equals(activePkg)){announce("等待回到"+app+"，不会操作其他应用");schedule(700);return;}
        if(now>deadline){deadline=now+180000;launch(target);announce("等待超时，正在重新打开"+app+"一次");acted(3500);return;}
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(root,nodes);Set<String> visible=words(nodes);
        if(platform==FlowRules.Platform.PIAOXINGQIU&&session.pending&&FlowRules.planetConsentDialog(visible)){
            AccessibilityNodeInfo agree=unique(nodes,"同意");announce("确认票星球授权弹窗 · "+(session.index+1)+" / "+total);
            if(agree==null||!clickOrTap(agree)){announce("等待票星球授权弹窗的“同意”按钮");schedule(450);return;}acted(650);return;
        }
        FlowRules.Step page=FlowRules.detect(platform,visible);if(!stable(page,now)){schedule(180);return;}last=page;
        String[] person=people.get(session.index);String name=person[0],id=person[1];
        String feedback=signal+" "+String.join(" ",visible);signal="";
        if(FillSession.permanentError(feedback)){finish(app+"提示资料、验证或频率问题，请处理后继续；已保留进度");return;}
        if(session.pending&&(feedback.contains("添加成功")||feedback.contains("保存成功")||feedback.contains("提交成功")))session.successSignal=true;
        if(page==FlowRules.Step.LIST){
            if(savedRow(nodes,name,id)){try{session.saved();checkpoint(false);completed=session.index;resetPerson();announce("列表已核对，确认存在 "+completed+" / "+total+" 人");schedule(160);}catch(Exception e){finish("进度保存失败，请核对已保存人员");}return;}
            if(listScanPhase==0){announce("核对人员是否已存在 · "+(session.index+1)+" / "+total);if(listScanMoves++<60&&scroll(nodes,false)){acted(260);return;}listScanPhase=1;listScanMoves=0;acted(180);return;}
            if(listScanPhase==1){if(listScanMoves++<60&&scroll(nodes,true)){acted(260);return;}listScanPhase=2;listScanMoves=0;if(session.pending){session.failedExplicitly();session.attempts=0;verificationBack=false;agreementClicked=false;try{checkpoint(false);}catch(Exception e){finish("进度保存失败");return;}announce("完整列表未找到当前人员，正在重新填写");acted(500);return;}}
            if(session.pending){announce("等待核对保存结果");schedule(500);return;}
            }
        if(page==FlowRules.Step.FORM){
            unknownSince=0;
            if(session.pending){
                if(session.successSignal&&!backSent){backSent=true;verificationBack=false;performGlobalAction(GLOBAL_ACTION_BACK);acted(380);return;}
                if(FillSession.transientError(feedback)){
                    session.failedExplicitly();agreementClicked=false;agreementAttempts=0;backSent=false;try{checkpoint(false);}catch(Exception e){finish("进度保存失败");return;}
                    if(session.attempts>=FillSession.MAX_ATTEMPTS){session.attempts=0;verificationBack=true;performGlobalAction(GLOBAL_ACTION_BACK);announce("连续失败，正在返回列表重新进入");acted(800);return;}
                    announce("添加失败，正在自动重试");acted(1200);return;
                }
                if((session.sentAt==0||now-session.sentAt>8000)&&!backSent){verificationBack=true;backSent=true;performGlobalAction(GLOBAL_ACTION_BACK);announce("仅返回一次，正在列表核对保存结果");acted(1400);return;}
                if(backSent&&now-session.sentAt>14000){paused=true;announce("未能回到人员列表，已暂停；请手动进入人员列表后点继续");schedule(700);return;}
                schedule(220);return;
            }
            fillAndSubmit(nodes,name,id,now);return;
        }
        if(page==FlowRules.Step.UNKNOWN){waitUnknown("当前页面未识别");return;}unknownSince=0;unknownPhase=0;
        AccessibilityNodeInfo button=switch(page){case HOME->best(nodes,"我的");case PROFILE->best(nodes,FlowRules.profileLabel(platform));case LIST->best(nodes,FlowRules.addLabel(platform));default->null;};
        if(button==null&&page==FlowRules.Step.LIST&&scroll(nodes,false)){acted(400);return;}
        String wanted=page==FlowRules.Step.LIST?FlowRules.addLabel(platform):page==FlowRules.Step.PROFILE?FlowRules.profileLabel(platform):"我的";
        if(button==null){announce("等待页面显示“"+wanted+"”");schedule(600);return;}
        if(!navigationReady(page.name()+":"+wanted,now))return;
        if(!clickOrTap(button)){announce("“"+wanted+"”未响应，稍后重试");acted(2200);return;}announce("已点击“"+wanted+"”，等待页面变化");acted(900);
    }
    private void fillAndSubmit(List<AccessibilityNodeInfo> nodes,String name,String id,long now){
        AccessibilityNodeInfo nf=null,df=null;List<AccessibilityNodeInfo> fields=new ArrayList<>();
        for(AccessibilityNodeInfo f:nodes)if(isTextField(f)){
            fields.add(f);
            String hint=FlowRules.norm(String.valueOf(f.getHintText())),t=FlowRules.norm(text(f));
            String desc=FlowRules.norm(String.valueOf(f.getContentDescription()));
            if(nf==null&&(hint.contains("姓名")||desc.contains("姓名")||t.equals("请输入姓名")||t.equals("请填写姓名")||t.equals(name)))nf=f;
            if(df==null&&(hint.contains("证件")||desc.contains("证件")||t.contains("证件号")||t.equals(id)))df=f;
        }
        fields.sort(Comparator.comparingInt(f->{Rect r=new Rect();f.getBoundsInScreen(r);return r.top;}));
        if((nf==null||df==null)&&fields.size()>=2){nf=fields.get(0);df=fields.get(1);}
        if(platform==FlowRules.Platform.PIAOXINGQIU&&nf==null){
            if(++nameActivationAttempts>8){paused=true;announce("票星球姓名输入框无法激活，已保留进度；请停留在填写页后点继续");return;}
            if(activatePiaoxingqiuField(nodes,true)){announce("正在激活姓名输入框 · "+(session.index+1)+" / "+total);acted(420);return;}
        }
        if(platform==FlowRules.Platform.PIAOXINGQIU&&df==null){
            if(++idActivationAttempts>8){paused=true;announce("票星球身份证输入框无法激活，已保留进度；请停留在填写页后点继续");return;}
            if(activatePiaoxingqiuField(nodes,false)){announce("正在激活身份证输入框 · "+(session.index+1)+" / "+total);acted(420);return;}
        }
        if(nf==null||df==null||nf.equals(df)){recover("正在重新识别姓名和证件输入框");return;}
        boolean nameReady=FlowRules.norm(text(nf)).equals(FlowRules.norm(name)),idReady=FlowRules.norm(text(df)).equals(FlowRules.norm(id));
        if(nameReady)nameActivationAttempts=0;if(idReady)idActivationAttempts=0;
        if(!nameReady||!idReady){
            AccessibilityNodeInfo field=!nameReady?nf:df;String value=!nameReady?name:id;FlowRules.FormStage stage=FlowRules.formStage(nameReady,idReady,false,false);announce(FlowRules.stageName(stage)+" · "+(session.index+1)+" / "+total);
            int activations=!nameReady?nameActivationAttempts:idActivationAttempts;
            if(platform==FlowRules.Platform.PIAOXINGQIU&&!field.isFocused()&&activations==0&&clickOrTap(field)){
                if(!nameReady)nameActivationAttempts=1;else idActivationAttempts=1;
                announce("正在激活"+(!nameReady?"姓名":"身份证")+"输入框");acted(350);return;
            }
            Bundle a=new Bundle();a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);boolean set=field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a);
            if(!set){if(!nameReady)nameActivationAttempts++;else idActivationAttempts++;if(Math.max(nameActivationAttempts,idActivationAttempts)>8){paused=true;announce("票星球输入框无法写入，已保留进度；请停留在填写页后点继续");return;}clickOrTap(field);announce("正在重新激活"+(!nameReady?"姓名":"身份证")+"输入框");acted(500);return;}
            acted(260);return;
        }
        if(keyboardVisible()){performGlobalAction(GLOBAL_ACTION_BACK);announce("资料已填写，正在收起键盘");acted(350);return;}
        AccessibilityNodeInfo agreement=platform==FlowRules.Platform.PIAOXINGQIU?best(nodes,"请阅读并同意"):best(nodes,"我已阅读并同意");
        List<AccessibilityNodeInfo> checks=new ArrayList<>();Rect label=new Rect();if(agreement!=null)agreement.getBoundsInScreen(label);
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&n.isCheckable()&&!String.valueOf(n.getClassName()).contains("Switch")){
            Rect r=new Rect();n.getBoundsInScreen(r);if(agreement!=null&&Math.abs(r.centerY()-label.centerY())<Math.max(label.height(),r.height())&&r.left<=label.right)checks.add(n);
        }
        if(checks.size()==1){if(!checks.get(0).isChecked()){announce(FlowRules.stageName(FlowRules.FormStage.AGREEMENT)+" · "+(session.index+1)+" / "+total);agreementAttempts++;if(!clickOrTap(checks.get(0))){recover("正在重新定位协议圆圈");return;}agreementClicked=true;acted(500);return;}agreementClicked=true;}
        else if(!agreementClicked){
            if(agreement==null){recover("正在查找协议文字和圆圈");return;}
            Rect circle=new Rect(Math.max(dp(4),label.left-dp(42)),label.centerY()-dp(22),Math.max(dp(44),label.left-dp(2)),label.centerY()+dp(22));
            announce(FlowRules.stageName(FlowRules.FormStage.AGREEMENT)+" · "+(session.index+1)+" / "+total);if(!tap(circle)){recover("正在重新定位协议圆圈");return;}agreementAttempts++;agreementClicked=true;acted(500);return;
        }
        if(!session.maySubmit(now)){schedule(220);return;}
        String confirmText=FlowRules.confirmLabel(platform);AccessibilityNodeInfo confirm=best(nodes,confirmText);if(confirm==null||!confirm.isEnabled()){
            agreementAttempts++;if(agreementAttempts%3==0)agreementClicked=false;
            announce("正在等待协议生效和“"+confirmText+"”按钮");acted(350);return;
        }
        announce(FlowRules.stageName(FlowRules.FormStage.CONFIRM)+" · "+(session.index+1)+" / "+total);try{checkpoint(true);}catch(Exception e){finish("无法保存提交进度，已暂停");return;}
        if(!clickOrTap(confirm)){try{checkpoint(false);}catch(Exception ignored){}session.pending=false;recover(confirmText+"按钮未响应");return;}
        session.submitted(now);announce(FlowRules.stageName(FlowRules.FormStage.SAVING)+" · "+(session.index+1)+" / "+total);acted(500);
    }
    private boolean activatePiaoxingqiuField(List<AccessibilityNodeInfo> nodes,boolean name){
        String[] placeholders=name?new String[]{"请填写姓名","请输入姓名","填写姓名"}:new String[]{"请填写证件号码","请输入证件号码","请输入证件号","填写证件号码"};
        for(String value:placeholders){AccessibilityNodeInfo node=best(nodes,value);if(node!=null&&clickOrTap(node))return true;}
        String[] labels=name?new String[]{"姓名"}:new String[]{"证件号码","证件号"};
        AccessibilityNodeInfo label=null;for(String value:labels){label=unique(nodes,value);if(label!=null)break;}
        if(label==null)return false;
        Rect lr=new Rect();label.getBoundsInScreen(lr);AccessibilityNodeInfo candidate=null;long score=Long.MAX_VALUE;
        for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&node.isEnabled()&&!node.equals(label)){
            Rect r=new Rect();node.getBoundsInScreen(r);if(r.isEmpty()||r.centerX()<=lr.centerX()||Math.abs(r.centerY()-lr.centerY())>Math.max(dp(52),lr.height()*2))continue;
            boolean inputLike=isTextField(node)||node.isClickable()||FlowRules.norm(text(node)).contains(name?"姓名":"证件");if(!inputLike)continue;
            long next=(long)Math.abs(r.centerY()-lr.centerY())*10000+Math.max(0,r.left-lr.right);if(next<score){score=next;candidate=node;}
        }
        return candidate!=null?clickOrTap(candidate):clickOrTap(label);
    }
    private boolean isTextField(AccessibilityNodeInfo n){
        if(!n.isVisibleToUser()||!n.isEnabled())return false;
        if(n.isEditable()||String.valueOf(n.getClassName()).contains("EditText"))return true;
        for(AccessibilityNodeInfo.AccessibilityAction a:n.getActionList())if(a.getId()==AccessibilityNodeInfo.ACTION_SET_TEXT)return true;
        return false;
    }
    private boolean keyboardVisible(){try{for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)return true;}catch(Exception ignored){}return false;}
    private boolean launch(String pkg){try{Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i==null)return false;startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}catch(Exception e){return false;}}
    private void allDone(){
        if(afterPkg.isEmpty()){finish("全部 "+total+" 人已在"+FlowRules.platformName(platform)+"保存");return;}
        restrict(target,afterPkg);if(!launch(afterPkg)){finish("全部资料已保存，但后续应用无法打开");return;}hopStage=1;hopAt=SystemClock.elapsedRealtime();announce("全部资料已保存，正在打开所选应用");schedule(250);
    }
    private void hop(long now){
        AccessibilityNodeInfo root=getRootInActiveWindow();String pkg=root==null?"":String.valueOf(root.getPackageName());
        if(hopStage==1){if(pkg.equals(afterPkg)){hopStage=2;hopAt=now;announce("已打开所选应用，即将返回"+FlowRules.platformName(platform));schedule(hopDelay);}else if(now-hopAt>6000)finish("资料已保存，系统未允许打开所选应用");else schedule(250);return;}
        if(hopStage==2){if(!pkg.equals(afterPkg)){finish("资料已保存；你已切换页面，自动返回已取消");return;}if(!launch(target)){finish("资料已保存，请手动返回"+FlowRules.platformName(platform));return;}hopStage=3;hopAt=now;schedule(250);return;}
        if(pkg.equals(target))finish("全部 "+total+" 人已保存，已返回"+FlowRules.platformName(platform));else if(now-hopAt>6000)finish("资料已保存，请手动返回"+FlowRules.platformName(platform));else schedule(250);
    }
}
