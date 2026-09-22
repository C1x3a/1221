package cn.c1clip.receiver;

import android.accessibilityservice.*;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
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
    private String target="",runtimeTarget="",batchId="",afterPkg="",configuredPkg="",signal="",maoyanPkg="",planetPkg="";
    private FlowRules.Platform platform=FlowRules.Platform.MAOYAN;
    private List<String[]> people=new ArrayList<>();private FillSession session;
    private long deadline,unknownSince,nextAction,hopAt,pageSince,lastNavAt,verificationListSince;private int hopStage,hopDelay,agreementAttempts,navAttempts,unknownPhase,listScanPhase,listScanMoves,nameActivationAttempts,idActivationAttempts,recoverAttempts;
    private boolean running,paused,agreementClicked,backSent,wroteName,wroteId,formOpenedFromList;
    private PlatformPageDetector.Page last=PlatformPageDetector.Page.UNKNOWN;
    private PlatformPageDetector.Page lastSeen=PlatformPageDetector.Page.UNKNOWN;
    private String lastNavKey="";
    private WindowManager windowManager;private View overlay;private TextView overlayTitle,overlayStatus;private Button overlayToggle,overlayMinimize;private ProgressBar overlayProgress;private LinearLayout overlayDetails;private WindowManager.LayoutParams overlayParams;private boolean overlayMinimized;
    @Override protected void onServiceConnected(){active=this;restrict(getPackageName());status="辅助填写已连接";sendBroadcast(new Intent(ReceiverService.UPDATE).setPackage(getPackageName()));}
    private void restrict(String... packages){AccessibilityServiceInfo info=getServiceInfo();if(info!=null){info.packageNames=packages;setServiceInfo(info);}}
    public static boolean systemEnabled(Context context){
        try{
            String enabled=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(enabled==null||enabled.isEmpty())return false;
            ComponentName self=new ComponentName(context,FillService.class);
            for(String value:enabled.split(":")){ComponentName component=ComponentName.unflattenFromString(value);if(self.equals(component))return true;}
        }catch(Exception ignored){}
        return false;
    }
    public static int connectionState(Context context){return !systemEnabled(context)?0:(active==null?1:2);}
    public static boolean available(){return active!=null;}
    public static boolean isRunning(){return active!=null&&active.running;}
    public static boolean start(String pkg,FlowRules.Platform platform,List<String[]> persons,String batch,String after,int stayMs,String maoyan,String planet,String configured) throws Exception {
        if(active==null||persons.isEmpty())return false;
        FillService s=active;s.finish("正在准备");s.target=pkg;s.runtimeTarget=pkg;s.platform=platform;s.batchId=batch;s.afterPkg=after.equals(pkg)?"":after;s.configuredPkg=configured;s.hopDelay=stayMs;s.maoyanPkg=maoyan;s.planetPkg=planet;
        s.people=new ArrayList<>();Set<String> unique=new HashSet<>();for(String[] person:persons)if(unique.add(person[0]+"|"+person[1]))s.people.add(person.clone());
        JSONObject saved=Vault.read(s).optJSONObject(s.progressKey());int index=0,attempts=0;boolean pending=false,verifying=false;String phaseName="";long sentAt=0;
        if(saved!=null&&batch.equals(saved.optString("batch"))&&pkg.equals(saved.optString("target"))&&platform.name().equals(saved.optString("platform",FlowRules.Platform.MAOYAN.name()))){index=saved.optInt("index");pending=saved.optBoolean("pending");attempts=saved.optInt("attempts",0);verifying=saved.optBoolean("verifying",false);phaseName=saved.optString("phase","");sentAt=saved.optLong("sentAt",0);}
        if(index<0||index>s.people.size())throw new Exception("进度不匹配，请接收新资料");
        // A newly started run must verify a previously completed list again. This catches people deleted in the ticket app.
        if(index==s.people.size()){index=0;pending=false;attempts=0;phaseName="";sentAt=0;}
        s.session=phaseName.isEmpty()?new FillSession(index,pending,verifying):new FillSession(index,phaseName,attempts,sentAt);s.session.attempts=attempts;completed=index;total=s.people.size();s.running=true;s.paused=false;s.hopStage=0;s.resetPerson();s.backSent=s.session.verificationStarted;s.restrict(pkg);s.showOverlay();s.announce("正在观察当前页面 · "+(index+1)+" / "+total+" 人");s.schedule(500);return true;
    }
    public static void cancel(String reason){if(active!=null&&active.running)active.finish(reason);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!running||hopStage!=0||event.getPackageName()==null||!isTaskPackage(event.getPackageName().toString()))return;
        if(event.getEventType()==AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED){StringBuilder b=new StringBuilder();for(CharSequence t:event.getText())b.append(t);signal=b.toString();}
        // Poll quickly but never bypass the minimum interval after an action.
        if(SystemClock.elapsedRealtime()>=nextAction)schedule(80);
    }
    @Override public void onInterrupt(){finish("系统中断，已暂停");}
    @Override public void onDestroy(){finish("辅助填写已关闭");active=null;super.onDestroy();}
    private void schedule(long delay){handler.removeCallbacks(step);handler.postDelayed(step,delay);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void announce(String text){status=text;updateOverlay();sendBroadcast(new Intent(ReceiverService.UPDATE).setPackage(getPackageName()));}
    private void finish(String reason){running=false;paused=false;handler.removeCallbacksAndMessages(null);removeOverlay();target="";runtimeTarget="";batchId="";afterPkg="";configuredPkg="";signal="";people.clear();restrict(getPackageName());announce(reason);}
    private void resetPerson(){deadline=SystemClock.elapsedRealtime()+180000;unknownSince=0;nextAction=0;pageSince=0;lastNavAt=0;verificationListSince=0;navAttempts=0;unknownPhase=0;listScanPhase=0;listScanMoves=0;nameActivationAttempts=0;idActivationAttempts=0;recoverAttempts=0;lastNavKey="";agreementClicked=false;agreementAttempts=0;backSent=false;wroteName=false;wroteId=false;formOpenedFromList=false;signal="";last=PlatformPageDetector.Page.UNKNOWN;lastSeen=PlatformPageDetector.Page.UNKNOWN;}
    private String progressKey(){return platform==FlowRules.Platform.PIAOXINGQIU?"fillProgress_PIAOXINGQIU":"fillProgress_MAOYAN";}
    private void checkpoint(boolean pending) throws Exception {synchronized(Vault.class){JSONObject state=Vault.read(this);state.put(progressKey(),new JSONObject().put("batch",batchId).put("target",target).put("platform",platform.name()).put("index",session.index).put("pending",session.needsVerification()).put("verifying",session.verificationStarted).put("phase",session.phase.name()).put("attempts",session.attempts).put("sentAt",session.sentAt));Vault.write(this,state);}}
    private void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out){if(node==null||out.size()>=1200)return;out.add(node);for(int i=0;i<node.getChildCount()&&out.size()<1200;i++)collect(node.getChild(i),out);}
    private String text(AccessibilityNodeInfo n){return n.getText()==null?"":n.getText().toString();}
    private Set<String> words(List<AccessibilityNodeInfo> nodes){
        Set<String> result=new LinkedHashSet<>();
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser())for(String value:nodeValues(n))if(!value.isEmpty())result.add(value);
        return result;
    }
    private List<String> nodeValues(AccessibilityNodeInfo n){
        ArrayList<String> out=new ArrayList<>(3);
        if(n==null)return out;
        String t=FlowRules.norm(text(n));if(!t.isEmpty())out.add(t);
        CharSequence d=n.getContentDescription();if(d!=null){String v=FlowRules.norm(d.toString());if(!v.isEmpty()&&!out.contains(v))out.add(v);}
        String id=n.getViewIdResourceName();if(id!=null){String v=FlowRules.norm(id);if(!v.isEmpty()&&!out.contains(v))out.add(v);}
        return out;
    }
    private int editableCount(List<AccessibilityNodeInfo> nodes){int count=0;for(AccessibilityNodeInfo n:nodes)if(isTextField(n))count++;return count;}
    private boolean hasClickableAncestor(AccessibilityNodeInfo node,int levels){
        for(int i=0;i<levels&&node!=null;i++,node=node.getParent())if(node.isVisibleToUser()&&node.isEnabled()&&node.isClickable())return true;
        return false;
    }
    private boolean hasClickableLabel(List<AccessibilityNodeInfo> nodes,String... labels){
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()){
            boolean match=false;for(String v:nodeValues(n))for(String label:labels)if(v.contains(FlowRules.norm(label))){match=true;break;}
            if(match&&hasClickableAncestor(n,10))return true;
        }
        return false;
    }
    private PlatformPageDetector.Snapshot snapshot(List<AccessibilityNodeInfo> nodes){
        Set<String> values=words(nodes);boolean masked=false;
        for(String v:values)if(v.matches(".*[0-9]{2,8}[*•●]{2,}[0-9Xx]{2,8}.*")){masked=true;break;}
        boolean add=platform==FlowRules.Platform.MAOYAN
                ?hasClickableLabel(nodes,"添加/修改观演人信息","修改观演人信息","添加观演人信息","添加观演人")
                :hasClickableLabel(nodes,"新增观演/赛人","新增观演人","添加观演/赛人","添加观演人");
        boolean confirm=platform==FlowRules.Platform.MAOYAN?hasClickableLabel(nodes,"确定","保存"):hasClickableLabel(nodes,"保存","确认");
        return new PlatformPageDetector.Snapshot(values,editableCount(nodes),masked,add,confirm);
    }
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
    private boolean stable(PlatformPageDetector.Page page,long now){if(page!=lastSeen){lastSeen=page;pageSince=now;lastNavKey="";navAttempts=0;return false;}return now-pageSince>=500;}
    private boolean navigationReady(String key,long now){
        if(!key.equals(lastNavKey)){lastNavKey=key;lastNavAt=0;navAttempts=0;}
        if(lastNavAt!=0&&now-lastNavAt<2200){schedule(Math.max(150,2200-(now-lastNavAt)));return false;}
        if(navAttempts>=3){paused=true;announce("同一入口连续 3 次没有响应，已暂停避免重复跳转；请确认页面后点继续");schedule(700);return false;}
        lastNavAt=now;navAttempts++;return true;
    }
    private void waitUnknown(String reason){
        long now=SystemClock.elapsedRealtime();
        if(unknownSince==0){unknownSince=now;unknownPhase=0;announce(reason+"，正在用兼容模式继续识别");schedule(600);return;}
        long waited=now-unknownSince;
        if(unknownPhase==0&&waited>=3500){unknownPhase=1;lastSeen=PlatformPageDetector.Page.UNKNOWN;pageSince=0;announce("页面结构与已知机型不同，正在持续识别；不会重新拉起应用或盲目返回");schedule(700);return;}
        if(waited>=12000){unknownPhase=2;paused=true;announce("当前页面仍无法安全识别，已暂停避免中断或重复填写；请停留在当前页面后点继续");schedule(800);return;}
        schedule(500);
    }
    private void recover(String reason){if(++recoverAttempts>8){paused=true;announce(reason+"连续未成功，已暂停避免循环；请确认当前页面后点继续");schedule(700);return;}announce(reason+"，等待当前页面重新识别（"+recoverAttempts+" / 8）");lastSeen=PlatformPageDetector.Page.UNKNOWN;pageSince=0;acted(900);}

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
    private void togglePause(){paused=!paused;if(paused)announce("已暂停，当前步骤已保留");else{unknownSince=0;pageSince=0;deadline=SystemClock.elapsedRealtime()+180000;if(session!=null&&session.pending){verificationListSince=0;listScanPhase=0;listScanMoves=0;}announce("继续识别当前页面");schedule(100);}}
    private void updateOverlay(){if(overlayTitle!=null)overlayTitle.setText(overlayMinimized?FlowRules.platformName(platform).substring(0,1)+" "+Math.min(total,completed+1)+"/"+total:FlowRules.platformName(platform)+"  "+Math.min(total,completed+1)+" / "+total);if(overlayStatus!=null)overlayStatus.setText(status);if(overlayToggle!=null)overlayToggle.setText(paused?"继续":"暂停");if(overlayProgress!=null){overlayProgress.setMax(Math.max(1,total));overlayProgress.setProgress(Math.min(total,completed+1));}}
    private void removeOverlay(){if(overlay!=null&&windowManager!=null)try{windowManager.removeView(overlay);}catch(Exception ignored){}overlay=null;overlayTitle=null;overlayStatus=null;overlayToggle=null;overlayMinimize=null;overlayProgress=null;overlayDetails=null;overlayMinimized=false;}
    private void openConfiguredApp(){
        if(!running)return;if(configuredPkg==null||configuredPkg.isEmpty()){announce("尚未设置要切换的软件，请回接收端设置");return;}if(configuredPkg.equals(target)){announce("设置的软件就是当前填写平台");return;}
        AccessibilityNodeInfo root=findTaskRoot(false);String activePkg=root==null||root.getPackageName()==null?"":root.getPackageName().toString();String destination=isTaskPackage(activePkg)?configuredPkg:target;String destinationName=destination.equals(target)?FlowRules.platformName(platform):"设置的软件";
        try{checkpoint(session.pending);restrictForTask(configuredPkg);if(!launch(destination)){announce("无法打开"+destinationName+"，填写进度仍保留");return;}announce("正在切换到"+destinationName+"，填写进度已保留");schedule(700);}catch(Exception e){announce("切换应用失败，填写任务仍保留");}
    }
    private boolean isTaskPackage(String pkg){return pkg!=null&&!pkg.isEmpty()&&(pkg.equals(target)||pkg.equals(runtimeTarget));}
    private void restrictForTask(String... extras){LinkedHashSet<String> packages=new LinkedHashSet<>();if(!target.isEmpty())packages.add(target);if(!runtimeTarget.isEmpty())packages.add(runtimeTarget);for(String extra:extras)if(extra!=null&&!extra.isEmpty())packages.add(extra);restrict(packages.toArray(new String[0]));}
    private AccessibilityNodeInfo findTaskRoot(boolean bindRecognized){
        List<AccessibilityNodeInfo> roots=new ArrayList<>();AccessibilityNodeInfo activeRoot=getRootInActiveWindow();if(activeRoot!=null)roots.add(activeRoot);
        try{for(AccessibilityWindowInfo window:getWindows())if((window.isActive()||window.isFocused())&&window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){AccessibilityNodeInfo root=window.getRoot();if(root!=null&&!roots.contains(root))roots.add(root);}}catch(Exception ignored){}
        AccessibilityNodeInfo bestTask=null;long bestTaskScore=Long.MIN_VALUE;
        for(AccessibilityNodeInfo root:roots){
            String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
            if(!isTaskPackage(pkg))continue;
            long score=rootScore(root);if(score>bestTaskScore){bestTaskScore=score;bestTask=root;}
        }
        if(bestTask!=null)return bestTask;
        if(bindRecognized){
            AccessibilityNodeInfo bestRecognized=null;String bestPkg="";long bestScore=Long.MIN_VALUE;
            for(AccessibilityNodeInfo root:roots){
                String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
                if(pkg.isEmpty()||pkg.equals(getPackageName())||pkg.equals(configuredPkg))continue;
                List<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(root,nodes);PlatformPageDetector.Snapshot snap=snapshot(nodes);
                if(!PlatformPageDetector.identifiesFlow(platform,snap))continue;
                long score=rootScore(nodes,snap);if(score>bestScore){bestScore=score;bestRecognized=root;bestPkg=pkg;}
            }
            if(bestRecognized!=null){runtimeTarget=bestPkg;restrictForTask(configuredPkg);announce("已识别"+FlowRules.platformName(platform)+"当前流程页面，继续当前步骤");return bestRecognized;}
        }
        return activeRoot;
    }
    private long rootScore(AccessibilityNodeInfo root){List<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(root,nodes);return rootScore(nodes,snapshot(nodes));}
    private long rootScore(List<AccessibilityNodeInfo> nodes,PlatformPageDetector.Snapshot snap){
        PlatformPageDetector.Result detected=PlatformPageDetector.detect(platform,snap);
        long score=nodes.size()*8L+snap.values.size()*30L;
        if(detected.page!=PlatformPageDetector.Page.UNKNOWN)score+=7000;
        if(snap.editableCount>=2)score+=3000;
        if(snap.maskedIdentity)score+=2200;
        if(snap.clickableAdd)score+=1800;
        if(snap.clickableConfirm)score+=1200;
        return score;
    }
    private boolean savedRow(List<AccessibilityNodeInfo> nodes,String name,String id){
        ArrayList<AccessibilityNodeInfo> idNodes=new ArrayList<>();
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()){
            boolean idMatch=false,nameMatch=false;
            for(String value:nodeValues(n)){if(FillSession.maskedIdMatches(value,id))idMatch=true;if(FillSession.maskedNameMatches(value,name))nameMatch=true;}
            if(idMatch&&nameMatch)return true;
            if(idMatch)idNodes.add(n);
        }
        for(AccessibilityNodeInfo idNode:idNodes){
            AccessibilityNodeInfo parent=idNode;
            for(int level=0;level<8&&parent!=null;level++,parent=parent.getParent()){
                List<AccessibilityNodeInfo> row=new ArrayList<>();collect(parent,row);
                for(AccessibilityNodeInfo candidate:row)if(candidate.isVisibleToUser())for(String value:nodeValues(candidate))if(FillSession.maskedNameMatches(value,name))return true;
            }
            Rect identityBounds=new Rect();idNode.getBoundsInScreen(identityBounds);
            int rowTolerance=Math.max(dp(96),getResources().getDisplayMetrics().heightPixels*10/100);
            for(AccessibilityNodeInfo candidate:nodes)if(candidate.isVisibleToUser()){
                boolean nameMatch=false;for(String value:nodeValues(candidate))if(FillSession.maskedNameMatches(value,name)){nameMatch=true;break;}
                if(!nameMatch)continue;
                Rect nameBounds=new Rect();candidate.getBoundsInScreen(nameBounds);
                if(!identityBounds.isEmpty()&&!nameBounds.isEmpty()&&Math.abs(nameBounds.centerY()-identityBounds.centerY())<=rowTolerance)return true;
            }
        }
        return false;
    }
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

        AccessibilityNodeInfo root=findTaskRoot(true);String activePkg=root==null||root.getPackageName()==null?"":root.getPackageName().toString();
        if(activePkg.equals(getPackageName())){paused=true;announce("已回到接收端，任务已暂停；点继续或平台按钮恢复");schedule(700);return;}
        if(root==null||!isTaskPackage(activePkg)){announce("等待回到"+app+"，不会操作其他应用");schedule(700);return;}
        if(now>deadline){paused=true;announce("当前任务等待超过 3 分钟，已暂停并保留状态");schedule(700);return;}

        List<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(root,nodes);
        PlatformPageDetector.Snapshot snap=snapshot(nodes);PlatformPageDetector.Result detected=PlatformPageDetector.detect(platform,snap);
        PlatformPageDetector.Page page=detected.page;
        if(!stable(page,now)){schedule(180);return;}last=page;

        String[] person=people.get(session.index);String name=person[0],id=person[1];
        String feedback=signal+" "+String.join(" ",snap.values);signal="";
        if(FillSession.permanentError(feedback)){finish(app+"提示资料、验证或频率问题，请处理后继续；进度已保留");return;}
        if(session.needsVerification()&&(feedback.contains("添加成功")||feedback.contains("保存成功")||feedback.contains("提交成功")))session.successSignal=true;

        if(page==PlatformPageDetector.Page.CONSENT_DIALOG){
            if(!session.needsVerification()){session.submitted(now);try{checkpoint(true);}catch(Exception e){finish("无法保存提交状态，已暂停");return;}}
            AccessibilityNodeInfo agree=unique(nodes,"同意");announce("确认票星球敏感信息授权 · "+(session.index+1)+" / "+total);
            if(agree==null||!clickOrTap(agree)){announce("等待授权弹窗“同意”按钮");schedule(450);return;}
            acted(700);return;
        }

        if(page==PlatformPageDetector.Page.PERSON_LIST){
            unknownSince=0;unknownPhase=0;
            if(savedRow(nodes,name,id)){
                try{session.saved();checkpoint(false);completed=session.index;resetPerson();announce("列表核对成功 · "+completed+" / "+total+" 人");schedule(220);}
                catch(Exception e){finish("进度保存失败，请核对已保存人员");}
                return;
            }

            if(session.needsVerification()){
                if(!session.verificationStarted){session.startedVerification();try{checkpoint(true);}catch(Exception e){finish("无法保存核对状态");return;}}
                if(verificationListSince==0){verificationListSince=now;listScanPhase=0;listScanMoves=0;}
                if(FillSession.shouldWaitForListSync(true,verificationListSince,now)){
                    long left=(FillSession.LIST_SYNC_GRACE_MS-(now-verificationListSince)+999)/1000;
                    announce("保存已返回人员列表，等待同步并核对（"+left+"秒）");schedule(300);return;
                }
                if(listScanPhase==0){
                    announce("正在核对已提交人员 · "+(session.index+1)+" / "+total);
                    if(listScanMoves++<24&&scroll(nodes,false)){acted(280);return;}
                    listScanPhase=1;listScanMoves=0;acted(180);return;
                }
                if(listScanPhase==1){
                    if(listScanMoves++<40&&scroll(nodes,true)){acted(280);return;}
                    listScanPhase=2;listScanMoves=0;
                }
                paused=true;try{checkpoint(true);}catch(Exception ignored){}
                announce("已提交但列表暂未核对到当前人员，已暂停；不会重新填写");
                schedule(700);return;
            }

            session.checkingExisting();
            if(listScanPhase==0){
                announce("先回到人员列表顶部核对 · "+(session.index+1)+" / "+total);
                if(listScanMoves++<24&&scroll(nodes,false)){acted(260);return;}
                listScanPhase=1;listScanMoves=0;acted(180);return;
            }
            if(listScanPhase==1){
                announce("正在核对人员是否已存在 · "+(session.index+1)+" / "+total);
                if(listScanMoves++<40&&scroll(nodes,true)){acted(260);return;}
                listScanPhase=2;listScanMoves=0;acted(180);return;
            }
            if(listScanPhase==2){
                announce("未发现当前人员，返回列表顶部准备新增");
                if(listScanMoves++<40&&scroll(nodes,false)){acted(260);return;}
                listScanPhase=3;listScanMoves=0;acted(180);return;
            }

            AccessibilityNodeInfo add=addPersonButton(nodes);
            if(add==null){announce("已确认人员列表，但暂未定位新增人员按钮");schedule(500);return;}
            if(!navigationReady("PERSON_LIST:ADD",now))return;
            session.openingForm();try{checkpoint(false);}catch(Exception e){finish("无法保存流程状态");return;}
            if(!clickAddPersonControl(nodes,add,navAttempts)){announce("新增人员按钮未响应，稍后重试");acted(1200);return;}
            formOpenedFromList=true;UiAdaptation.rememberAdd(this,platform,add);announce("已进入新增人员步骤，等待表单");acted(800);return;
        }

        if(page==PlatformPageDetector.Page.ADD_FORM){
            unknownSince=0;unknownPhase=0;
            if(session.needsVerification()){
                if(FillSession.transientError(feedback)){
                    session.failedExplicitly();agreementClicked=false;agreementAttempts=0;backSent=false;
                    try{checkpoint(false);}catch(Exception e){finish("进度保存失败");return;}
                    if(session.attempts>=FillSession.MAX_ATTEMPTS){paused=true;announce("连续保存失败，已暂停");schedule(700);return;}
                    announce("平台明确提示保存失败，允许重新处理当前人员");acted(900);return;
                }
                if(FillSession.shouldPauseUncertainForm(true,session.sentAt,now)){
                    paused=true;try{checkpoint(true);}catch(Exception ignored){}
                    announce("当前人员已经提交，仍停留在表单；已暂停且不会重新填写");
                    schedule(700);return;
                }
                announce("当前人员已经提交，等待平台返回人员列表；不会重复填写");schedule(300);return;
            }
            session.filling();try{checkpoint(false);}catch(Exception e){finish("无法保存填写状态");return;}
            fillAndSubmit(nodes,name,id,now);return;
        }

        if(page==PlatformPageDetector.Page.PROFILE){
            unknownSince=0;unknownPhase=0;
            AccessibilityNodeInfo entry=findProfileEntry(nodes);
            if(entry==null){announce("已识别个人页，等待人员信息入口");schedule(500);return;}
            if(!navigationReady("PROFILE:PERSON",now))return;
            if(!clickOrTap(entry)){announce("人员信息入口未响应");acted(900);return;}
            announce(session.needsVerification()?"正在返回人员列表核对已提交结果":"正在进入人员列表");acted(800);return;
        }

        if(page==PlatformPageDetector.Page.HOME){
            unknownSince=0;unknownPhase=0;
            AccessibilityNodeInfo mine=best(nodes,"我的");
            if(mine==null){announce("已识别首页，等待“我的”入口");schedule(500);return;}
            if(!navigationReady("HOME:MY",now))return;
            if(!clickOrTap(mine)){announce("“我的”入口未响应");acted(900);return;}
            announce("已点击“我的”，重新识别下一页面");acted(800);return;
        }

        waitUnknown("当前页面证据不足（"+detected.evidence+"）");
    }

    private AccessibilityNodeInfo findProfileEntry(List<AccessibilityNodeInfo> nodes){
        AccessibilityNodeInfo result=platform==FlowRules.Platform.PIAOXINGQIU?best(nodes,"观演/赛人"):best(nodes,"观演人信息");
        if(result==null&&platform==FlowRules.Platform.PIAOXINGQIU)result=best(nodes,"观演人");
        return result;
    }
    private boolean clickAddPersonControl(List<AccessibilityNodeInfo> nodes,AccessibilityNodeInfo preferred,int attempt){
        if(platform!=FlowRules.Platform.MAOYAN)return clickOrTap(preferred);
        if(attempt==1){int level=0;for(AccessibilityNodeInfo node=preferred;node!=null&&level++<10;node=node.getParent()){if(node.isVisibleToUser()&&node.isEnabled()&&node.isClickable()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;}return clickOrTap(preferred);}
        if(attempt==2){AccessibilityNodeInfo structural=structuralAddPersonButton(nodes);if(structural!=null&&clickOrTap(structural))return true;}
        Rect label=new Rect();if(preferred!=null)preferred.getBoundsInScreen(label);if(label.isEmpty())return false;
        int width=getResources().getDisplayMetrics().widthPixels;Rect buttonArea=new Rect(dp(24),Math.max(0,label.centerY()-dp(34)),Math.max(dp(48),width-dp(24)),label.centerY()+dp(34));return tap(buttonArea);
    }
    private AccessibilityNodeInfo addPersonButton(List<AccessibilityNodeInfo> nodes){
        AccessibilityNodeInfo result=usableAddCandidate(best(nodes,FlowRules.addLabel(platform)));
        if(platform==FlowRules.Platform.MAOYAN){
            if(result==null)result=usableAddCandidate(best(nodes,"修改观演人信息"));
            if(result==null)result=usableAddCandidate(best(nodes,"添加观演人信息"));
            if(result==null)result=usableAddCandidate(best(nodes,"添加观演人"));
            if(result==null)result=usableAddCandidate(best(nodes,"修改观演人"));
        }else{
            if(result==null)result=usableAddCandidate(best(nodes,"新增观演/赛人"));
            if(result==null)result=usableAddCandidate(best(nodes,"新增观演人"));
            if(result==null)result=usableAddCandidate(best(nodes,"添加观演人"));
        }
        if(result!=null)return result;
        result=UiAdaptation.findAdd(this,platform,nodes);if(usableAddCandidate(result)!=null)return result;
        return structuralAddPersonButton(nodes);
    }
    private AccessibilityNodeInfo usableAddCandidate(AccessibilityNodeInfo node){
        if(node==null)return null;int level=0;for(AccessibilityNodeInfo p=node;p!=null&&level++<10;p=p.getParent())if(p.isVisibleToUser()&&p.isEnabled()&&p.isClickable())return node;
        Rect r=new Rect();node.getBoundsInScreen(r);if(r.isEmpty())return null;int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels;
        if(r.width()>=width*42/100&&r.height()>=dp(30)&&r.centerY()>=height*12/100&&r.centerY()<=height*94/100)return node;
        return null;
    }
    private boolean hasMaskedPersonRow(List<AccessibilityNodeInfo> nodes){
        for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&FlowRules.norm(text(node)).matches(".*[0-9]{3,}[*•●]+[0-9Xx]{3,}.*"))return true;
        return false;
    }
    private AccessibilityNodeInfo structuralAddPersonButton(List<AccessibilityNodeInfo> nodes){
        boolean pageEvidence=hasMaskedPersonRow(nodes);for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()){String value=FlowRules.norm(text(node));if(value.contains("常用信息")||value.contains("观演人")||value.contains("实名信息")){pageEvidence=true;break;}}
        if(!pageEvidence)return null;
        int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo candidate=null;long bestScore=Long.MIN_VALUE;
        for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&node.isEnabled()){
            Rect r=new Rect();node.getBoundsInScreen(r);if(r.isEmpty()||r.width()<width*45/100||r.height()<dp(34)||r.height()>Math.max(dp(180),height*18/100))continue;
            if(r.centerY()<height*6/100||r.centerY()>height*52/100)continue;
            String value=FlowRules.norm(text(node));long textBonus=(value.contains("添加")||value.contains("新增")||value.contains("修改"))?1800000L:0;
            long score=textBonus+(node.isClickable()?1200000L:0)+(long)r.width()*100-Math.abs(r.centerY()-height*22/100)*25L;
            if(score>bestScore){bestScore=score;candidate=node;}
        }
        return candidate;
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
        fields.removeIf(f->{Rect r=new Rect();f.getBoundsInScreen(r);return r.isEmpty()||r.width()<dp(40)||r.height()<dp(20);});
        fields.sort(Comparator.comparingInt(f->{Rect r=new Rect();f.getBoundsInScreen(r);return r.top;}));
        if(nf==null)nf=fieldNearLabel(nodes,fields,new String[]{"姓名"});
        if(df==null)df=fieldNearLabel(nodes,fields,new String[]{"证件号码","证件号","身份证"});
        if((nf==null||df==null)&&fields.size()>=2){if(nf==null)nf=fields.get(0);if(df==null){for(AccessibilityNodeInfo candidate:fields)if(!candidate.equals(nf)){df=candidate;break;}}}
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
            if(!nameReady)wroteName=true;else wroteId=true;
            acted(260);return;
        }
        if(!session.pending&&nameReady&&idReady&&!formOpenedFromList&&!wroteName&&!wroteId&&session.attempts==0){
            paused=true;announce("检测到当前表单已存在同一人的完整资料，但本次没有确认从人员列表新建进入；已暂停避免重复保存，请返回人员列表后点继续");schedule(700);return;
        }
        AccessibilityNodeInfo agreement=platform==FlowRules.Platform.PIAOXINGQIU?best(nodes,"请阅读并同意"):best(nodes,"我已阅读并同意");
        // Never use GLOBAL_ACTION_BACK to hide the IME here. On several Xiaomi/HyperOS builds
        // the IME window can remain reported for one frame after it has visually disappeared,
        // causing BACK to leave the whole form and create an endless re-entry/refill loop.
        if(agreement==null&&keyboardVisible()){
            AccessibilityNodeInfo safe=best(nodes,"证件类型");if(safe==null)safe=best(nodes,"姓名");
            if(safe!=null){Rect sr=new Rect();safe.getBoundsInScreen(sr);if(!sr.isEmpty()){tap(sr);announce("资料已填写，正在安全收起键盘");acted(320);return;}}
        }
        List<AccessibilityNodeInfo> checks=new ArrayList<>();Rect label=new Rect();if(agreement!=null)agreement.getBoundsInScreen(label);
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&n.isCheckable()&&!String.valueOf(n.getClassName()).contains("Switch")){
            Rect r=new Rect();n.getBoundsInScreen(r);if(agreement!=null&&Math.abs(r.centerY()-label.centerY())<Math.max(label.height(),r.height())&&r.left<=label.right)checks.add(n);
        }
        if(checks.size()==1){UiAdaptation.rememberAgreement(this,platform,checks.get(0));if(!checks.get(0).isChecked()){announce(FlowRules.stageName(FlowRules.FormStage.AGREEMENT)+" · "+(session.index+1)+" / "+total);agreementAttempts++;if(!clickOrTap(checks.get(0))){recover("正在重新定位协议圆圈");return;}agreementClicked=true;acted(500);return;}agreementClicked=true;}
        else if(!agreementClicked){
            if(agreement==null){recover("正在查找协议文字和圆圈");return;}
            AccessibilityNodeInfo learned=UiAdaptation.findAgreement(this,platform,nodes,label);
            AccessibilityNodeInfo nearby=learned!=null?learned:nearbyAgreementControl(nodes,label);
            announce(FlowRules.stageName(FlowRules.FormStage.AGREEMENT)+" · "+(session.index+1)+" / "+total);
            if(nearby!=null&&clickOrTap(nearby)){UiAdaptation.rememberAgreement(this,platform,nearby);agreementAttempts++;agreementClicked=true;acted(500);return;}
            int gap=Math.max(dp(24),Math.min(dp(54),Math.max(label.height(),dp(18))*3/2));int half=Math.max(dp(16),Math.min(dp(28),Math.max(label.height(),dp(18))));
            Rect circle=new Rect(Math.max(dp(2),label.left-gap-half),label.centerY()-half,Math.max(dp(4),label.left-gap+half),label.centerY()+half);
            if(!tap(circle)){recover("正在重新定位协议圆圈");return;}agreementAttempts++;agreementClicked=true;acted(500);return;
        }
        if(!session.maySubmit(now)){schedule(220);return;}
        String confirmText=FlowRules.confirmLabel(platform);AccessibilityNodeInfo confirm=best(nodes,confirmText);if(confirm==null||!confirm.isEnabled()){
            agreementAttempts++;if(agreementAttempts%3==0)agreementClicked=false;
            announce("正在等待协议生效和“"+confirmText+"”按钮");acted(350);return;
        }
        announce(FlowRules.stageName(FlowRules.FormStage.CONFIRM)+" · "+(session.index+1)+" / "+total);session.submitted(now);verificationListSince=0;listScanPhase=0;listScanMoves=0;try{checkpoint(true);}catch(Exception e){session.failedExplicitly();finish("无法保存提交进度，已暂停");return;}
        if(!clickOrTap(confirm)){session.failedExplicitly();try{checkpoint(false);}catch(Exception ignored){}recover(confirmText+"按钮未响应");return;}
        announce(FlowRules.stageName(FlowRules.FormStage.SAVING)+" · "+(session.index+1)+" / "+total);acted(500);
    }
    private AccessibilityNodeInfo fieldNearLabel(List<AccessibilityNodeInfo> nodes,List<AccessibilityNodeInfo> fields,String[] labels){
        AccessibilityNodeInfo label=null;for(String value:labels){label=unique(nodes,value);if(label!=null)break;}if(label==null)return null;
        Rect lr=new Rect();label.getBoundsInScreen(lr);if(lr.isEmpty())return null;AccessibilityNodeInfo result=null;long best=Long.MAX_VALUE;int tolerance=Math.max(dp(72),getResources().getDisplayMetrics().heightPixels*9/100);
        for(AccessibilityNodeInfo field:fields){Rect r=new Rect();field.getBoundsInScreen(r);if(r.isEmpty()||Math.abs(r.centerY()-lr.centerY())>tolerance)continue;long score=(long)Math.abs(r.centerY()-lr.centerY())*10000+Math.abs(r.left-lr.right);if(r.centerX()<lr.centerX())score+=1000000;if(score<best){best=score;result=field;}}
        return result;
    }
    private AccessibilityNodeInfo nearbyAgreementControl(List<AccessibilityNodeInfo> nodes,Rect label){
        if(label==null||label.isEmpty())return null;AccessibilityNodeInfo result=null;long best=Long.MAX_VALUE;int tolerance=Math.max(dp(72),getResources().getDisplayMetrics().heightPixels*7/100);
        for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&node.isEnabled()){
            Rect r=new Rect();node.getBoundsInScreen(r);if(r.isEmpty()||Math.abs(r.centerY()-label.centerY())>tolerance)continue;
            if(r.width()>getResources().getDisplayMetrics().widthPixels*30/100||r.height()>getResources().getDisplayMetrics().heightPixels*10/100)continue;
            if(!node.isCheckable()&&!node.isClickable())continue;
            long score=(long)Math.abs(r.centerY()-label.centerY())*10000+Math.abs(r.centerX()-label.left);if(r.centerX()>label.right)score+=300000;if(node.isCheckable())score-=500000;if(score<best){best=score;result=node;}}
        return result;
    }
    private boolean activatePiaoxingqiuField(List<AccessibilityNodeInfo> nodes,boolean name){
        String[] placeholders=name?new String[]{"请填写姓名","请输入姓名","填写姓名"}:new String[]{"请填写证件号码","请输入证件号码","请输入证件号","填写证件号码"};
        for(String value:placeholders){AccessibilityNodeInfo node=best(nodes,value);if(node!=null&&clickOrTap(node))return true;}
        String[] labels=name?new String[]{"姓名"}:new String[]{"证件号码","证件号"};
        AccessibilityNodeInfo label=null;for(String value:labels){label=unique(nodes,value);if(label!=null)break;}
        if(label==null)return false;
        Rect lr=new Rect();label.getBoundsInScreen(lr);AccessibilityNodeInfo candidate=null;long score=Long.MAX_VALUE;int verticalTolerance=Math.max(dp(64),getResources().getDisplayMetrics().heightPixels*8/100);
        for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&node.isEnabled()&&!node.equals(label)){
            Rect r=new Rect();node.getBoundsInScreen(r);if(r.isEmpty()||r.centerX()<=lr.centerX()||Math.abs(r.centerY()-lr.centerY())>Math.max(verticalTolerance,lr.height()*3))continue;
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
        restrictForTask(afterPkg);if(!launch(afterPkg)){finish("全部资料已保存，但后续应用无法打开");return;}hopStage=1;hopAt=SystemClock.elapsedRealtime();announce("全部资料已保存，正在打开所选应用");schedule(250);
    }
    private void hop(long now){
        AccessibilityNodeInfo root=getRootInActiveWindow();String pkg=root==null?"":String.valueOf(root.getPackageName());
        if(hopStage==1){if(pkg.equals(afterPkg)){hopStage=2;hopAt=now;announce("已打开所选应用，即将返回"+FlowRules.platformName(platform));schedule(hopDelay);}else if(now-hopAt>6000)finish("资料已保存，系统未允许打开所选应用");else schedule(250);return;}
        if(hopStage==2){if(!pkg.equals(afterPkg)){finish("资料已保存；你已切换页面，自动返回已取消");return;}if(!launch(target)){finish("资料已保存，请手动返回"+FlowRules.platformName(platform));return;}hopStage=3;hopAt=now;schedule(250);return;}
        if(isTaskPackage(pkg))finish("全部 "+total+" 人已保存，已返回"+FlowRules.platformName(platform));else if(now-hopAt>6000)finish("资料已保存，请手动返回"+FlowRules.platformName(platform));else schedule(250);
    }
}
