package cn.c1clip.receiver;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout body,contents;private TextView network,fillStatus,copyStatus;
    private JSONObject batch;private List<String[]> people=new ArrayList<>();private String shown="";private boolean copying=false,resumed=false;private int copied=0;private String copyId="";
    private final Runnable copyStep=this::copyNext;
    private int blue=Color.rgb(21,93,251);
    private LinearLayout card;
    private TextView count,afterLabel;private Button stopTask;private ProgressBar taskProgress;
    private int ink=Color.rgb(17,36,65),muted=Color.rgb(103,119,142);
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.GradientDrawable rounded(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout section(String title,String subtitle){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(14),dp(18),dp(18));box.setBackground(rounded(Color.WHITE,18));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(14));body.addView(box,lp);card=box;label(box,title,18,ink,true);if(!subtitle.isEmpty())label(box,subtitle,13,muted,false);return box;}
    private TextView label(LinearLayout parent,String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,1);t.setPadding(0,dp(6),0,dp(6));parent.addView(t);return t;}
    private Button action(LinearLayout parent,String title,boolean primary,Runnable task){Button b=new Button(this);b.setText(title);b.setTextSize(16);b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:blue);b.setBackground(rounded(primary?blue:Color.rgb(238,244,255),12));b.setMinHeight(dp(48));b.setStateListAnimator(null);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.setMargins(0,dp(10),0,0);parent.addView(b,lp);b.setOnClickListener(v->task.run());return b;}
    private Button platformAction(LinearLayout parent,String title,int color,Runnable task){Button b=new Button(this);b.setText(title);b.setTextSize(16);b.setTypeface(null,1);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(rounded(color,14));b.setStateListAnimator(null);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(62),1);lp.setMargins(dp(4),dp(10),dp(4),0);parent.addView(b,lp);b.setOnClickListener(v->task.run());return b;}
    private LinearLayout fold(LinearLayout parent,String title){LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setVisibility(View.GONE);Button toggle=action(parent,title+"  ＋",false,()->{});toggle.setOnClickListener(v->{boolean open=panel.getVisibility()==View.GONE;panel.setVisibility(open?View.VISIBLE:View.GONE);toggle.setText(title+(open?"  −":"  ＋"));});parent.addView(panel);return panel;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(12),dp(16),dp(24));scroll.addView(body);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(245,248,253));setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{body.setPadding(dp(16),dp(12)+insets.getSystemWindowInsetTop(),dp(16),dp(24)+insets.getSystemWindowInsetBottom());return insets;});
        label(body,"多机接收",28,ink,true);label(body,"0.3  ·  接收、整理、填写",13,muted,false);
        section("设备连接","");network=label(card,ReceiverService.status,14,muted,false);
        action(card,"连接与权限设置",false,this::showSettings);
        section("1 · 接收信息","电脑发送后自动更新；姓名与证件号分别保留。");count=label(card,"等待资料",14,blue,true);contents=new LinearLayout(this);contents.setOrientation(LinearLayout.VERTICAL);card.addView(contents);
        section("2 · 整理复制信息","信息为空时不发送；姓名与身份证会分别进入输入法剪贴板历史。");copyStatus=label(card,"默认间隔 1.5 秒，逐条复制",13,muted,false);LinearLayout tools=fold(card,"展开复制与内容管理");
        Spinner interval=new Spinner(this);interval.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"复制间隔 0.8 秒","复制间隔 1.5 秒","复制间隔 2.5 秒"}));interval.setSelection(getPreferences(0).getInt("intervalChoice",1));interval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long i){getPreferences(0).edit().putInt("intervalChoice",pos).apply();}public void onNothingSelected(AdapterView<?> p){}});tools.addView(interval);
        EditText keyboard=new EditText(this);keyboard.setHint("点这里唤起输入法");tools.addView(keyboard);action(tools,"逐条复制全部",true,this::copyAll);action(tools,"停止复制",false,()->stopCopy("已停止复制"));
        action(tools,"输入法历史中已全部出现",false,()->{try{if(batch==null)return;JSONObject receipt=Vault.read(this).optJSONObject("receipt");if(receipt==null||receipt.optInt("copied")!=batch.getJSONArray("items").length()){toast("请先完成复制并检查输入法历史");return;}Vault.receipt(this,batch.getString("id"),"confirmed",receipt.getInt("copied"),"");sendReceipt();toast("确认已发送");}catch(Exception e){toast("确认保存失败");}});
        action(tools,"清空当前内容",false,()->{stopCopy("已清空");FillService.cancel("内容已清空");try{synchronized(Vault.class){JSONObject state=Vault.read(this),b=state.optJSONObject("batch");if(b!=null){state.put("receipt",new JSONObject().put("t","receipt").put("id",b.getString("id")).put("status","deleted").put("copied",0));state.remove("batch");state.remove("fillProgress");Vault.write(this,state);}}shown="";refresh();sendReceipt();}catch(Exception e){toast("清空失败");}});
        section("3 · 跳转填写信息","选择平台后，自动填写全部人员、勾选协议并逐人确认保存。");
        LinearLayout appRow=new LinearLayout(this);appRow.setOrientation(LinearLayout.HORIZONTAL);card.addView(appRow,new LinearLayout.LayoutParams(-1,-2));platformAction(appRow,"猫眼",Color.rgb(255,77,79),()->target("猫眼",true));platformAction(appRow,"票星球",Color.rgb(112,76,255),()->target("票星球",true));
        fillStatus=label(card,FillService.status,14,muted,false);taskProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);taskProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(blue));card.addView(taskProgress,new LinearLayout.LayoutParams(-1,dp(5)));
        stopTask=action(card,"暂停填写",false,()->FillService.cancel("已暂停，进度已保留"));stopTask.setVisibility(View.GONE);
        LinearLayout after=fold(card,"完成后切换应用");Switch hop=new Switch(this);hop.setText("开启完成后切换");hop.setTextColor(ink);hop.setTextSize(15);hop.setChecked(getPreferences(0).getBoolean("afterEnabled",false));hop.setOnCheckedChangeListener((b,checked)->getPreferences(0).edit().putBoolean("afterEnabled",checked).apply());after.addView(hop);
        afterLabel=label(after,"当前："+getPreferences(0).getString("afterName","未选择应用"),14,muted,false);action(after,"选择切换应用",false,()->chooseInstalled("选择完成后打开的应用","",(pkg,name)->{getPreferences(0).edit().putString("afterPkg",pkg).putString("afterName",name).apply();afterLabel.setText("当前："+name);}));
        Spinner stay=new Spinner(this);stay.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"停留 1 秒后返回","停留 2 秒后返回","停留 3 秒后返回","停留 5 秒后返回"}));stay.setSelection(getPreferences(0).getInt("stay",1));stay.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long i){getPreferences(0).edit().putInt("stay",pos).apply();}public void onNothingSelected(AdapterView<?> p){}});after.addView(stay);
        label(body,"息屏可接收 · 填写时请保持手机解锁",12,muted,false);refresh();
        try{if(Vault.read(this).optJSONObject("pair")!=null)startReceive();}catch(Exception ignored){}
    }
    private void showSettings(){
        ScrollView scroll=new ScrollView(this);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(8),dp(20),dp(20));scroll.addView(panel);
        label(panel,"粘贴原电脑端对应手机的配对链接。新版启用后，停止旧 APP 的接收服务。",14,muted,false);
        EditText input=new EditText(this);input.setHint("粘贴配对链接");input.setMinLines(2);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setAutofillHints((String[])null);panel.addView(input);
        action(panel,"保存配对并接收",true,()->{try{JSONObject pair=Protocol.pair(input.getText().toString());FillService.cancel("配对已更新");stopCopy("配对已更新");stopService(new Intent(this,ReceiverService.class));Vault.savePair(this,pair);input.setText("");shown="";handler.postDelayed(this::startReceive,500);refresh();toast("已保存："+pair.getString("name"));}catch(Exception e){toast("配对失败，请检查链接");}});
        action(panel,"开始后台接收",false,this::startReceive);action(panel,"停止后台接收",false,()->{FillService.cancel("已停止");stopService(new Intent(this,ReceiverService.class));});
        action(panel,"开启辅助填写权限",false,()->new AlertDialog.Builder(this).setTitle("辅助填写权限").setMessage("点击猫眼或票星球后，服务会读取所选应用控件，填写全部人员、勾选协议并逐人确认保存；如启用后续切换，还会打开你选择的应用并返回填写平台。资料、验证码或未知结果问题会暂停，不上传页面内容。请先阅读对应平台的实名与授权说明后使用。").setPositiveButton("前往设置",(d,w)->safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("取消",null).show());
        action(panel,"允许后台持续联网",false,()->safeStart(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()))));action(panel,"打开应用系统设置",false,()->safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        action(panel,"重新选择猫眼 / 票星球",false,()->{getPreferences(0).edit().remove("app_猫眼").remove("app_票星球").apply();toast("下次点击时重新选择");});
        action(panel,"处理上次结果不明的提交",false,()->new AlertDialog.Builder(this).setTitle("先核对平台人员列表").setMessage("只有确认当前人员尚未保存时，才解除等待状态。已经保存的人员请直接在列表页面重试，程序会先检查列表。").setPositiveButton("已核对未保存，允许重试",(d,w)->{FillService.cancel("准备重试");try{synchronized(Vault.class){JSONObject state=Vault.read(this),progress=state.optJSONObject("fillProgress");if(progress!=null){progress.put("pending",false);Vault.write(this,state);}}toast("已解除等待，可点击对应平台继续");}catch(Exception e){toast("更新失败");}}).setNegativeButton("取消",null).show());
        label(panel,"明确失败时最多尝试 6 次，未确认结果时暂停。后台省电设置因小米系统版本而异。",12,muted,false);
        new AlertDialog.Builder(this).setTitle("连接与权限").setView(scroll).setPositiveButton("完成",null).show();
    }
    private void startReceive(){try{if(Vault.read(this).optJSONObject("pair")==null){toast("请先粘贴电脑配对链接");return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);startForegroundService(new Intent(this,ReceiverService.class));toast("后台接收已启动");}catch(Exception e){toast("启动失败，请重新配对或检查系统设置");}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void safeStart(Intent i){try{startActivity(i);}catch(Exception e){toast("无法打开，请在手机设置里手动查找该选项");}}
    @Override protected void onResume(){super.onResume();resumed=true;handler.post(refreshTick);}
    @Override protected void onPause(){super.onPause();resumed=false;handler.removeCallbacks(refreshTick);stopCopy("页面离开前台，已停止复制");}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus)stopCopy("页面失去焦点，已停止复制");}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private final Runnable refreshTick=new Runnable(){public void run(){refresh();if(resumed)handler.postDelayed(this,1200);}};
    private void refresh(){try{
        JSONObject state=Vault.read(this),pair=state.optJSONObject("pair");network.setText((pair==null?"未配对":pair.optString("name"))+"\n"+ReceiverService.status);fillStatus.setText(FillService.status);taskProgress.setMax(Math.max(1,FillService.total));taskProgress.setProgress(FillService.completed);stopTask.setVisibility(FillService.isRunning()?View.VISIBLE:View.GONE);
        JSONObject next=Vault.latest(this);String nextId=next==null?"empty":next.optString("id");if(nextId.equals(shown))return;
        stopCopy("收到最新内容，已停止旧内容复制");batch=next;shown=nextId;contents.removeAllViews();people=new ArrayList<>();
        if(batch==null){count.setText("等待资料");label(contents,"电脑发送后，可选择猫眼或票星球填写。",14,muted,false);}
        else{JSONArray items=batch.getJSONArray("items");try{people=Protocol.people(items);}catch(Exception e){copyStatus.setText(e.getMessage());}
            count.setText(items.length()+" 条信息 · "+people.size()+" 人");
            LinearLayout details=fold(contents,"查看全部资料");for(int i=0;i<items.length();i++)label(details,String.format(java.util.Locale.ROOT,"%02d  %s",i+1,items.getString(i)),15,ink,false);
            StringBuilder names=new StringBuilder();for(int i=0;i<Math.min(6,people.size());i++){if(i>0)names.append("、");names.append(people.get(i)[0]);}if(people.size()>6)names.append(" 等");if(names.length()>0)label(contents,names.toString(),16,ink,true);
        }
    }catch(Exception e){network.setText("读取本机数据失败，请重新打开应用；不要清除数据以免丢失配对");}}
    private void sendReceipt(){try{if(!ReceiverService.status.equals("未启动接收")&&!ReceiverService.status.equals("接收已停止"))startService(new Intent(this,ReceiverService.class).setAction("RECEIPT"));}catch(Exception ignored){}}
    private void copyAll(){if(batch==null||copying)return;try{handler.removeCallbacks(copyStep);copying=true;copied=0;copyId=batch.getString("id");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);copyNext();}catch(Exception e){stopCopy("复制失败");}}
    private void copyNext(){if(!copying)return;try{
        JSONObject latest=Vault.latest(this);if(!resumed||!hasWindowFocus()||latest==null||!copyId.equals(latest.optString("id"))){stopCopy("页面或内容已改变，请重新开始");return;}
        JSONArray items=latest.getJSONArray("items");getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("独立信息",items.getString(copied)));copied++;copyStatus.setText("已复制 "+copied+" / "+items.length()+" 条，请检查输入法历史");
        boolean done=copied==items.length();Vault.receipt(this,copyId,done?"copied":"copying",copied,"");sendReceipt();if(done){copying=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}else handler.postDelayed(copyStep,new int[]{800,1500,2500}[getPreferences(0).getInt("intervalChoice",1)]);
    }catch(Exception e){stopCopy("复制失败，请回到前台重试");}}
    private void stopCopy(String reason){if(!copying)return;copying=false;handler.removeCallbacks(copyStep);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);copyStatus.setText(reason);try{Vault.receipt(this,copyId,"error",copied,reason);sendReceipt();}catch(Exception ignored){}}
    private void target(String label,boolean fill){
        if(fill){if(people.isEmpty()){toast("请先接收并核对姓名和身份证");return;}if(!FillService.available()){toast("请先点击‘启用辅助填写权限’");return;}}
        String pkg=getPreferences(0).getString("app_"+label,"");if(pkg.isEmpty()){chooseApp(label,fill);return;}launch(label,pkg,fill);
    }
    private interface AppChoice{void selected(String pkg,String name);}
    private void chooseInstalled(String title,String preferred,AppChoice choice){
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> raw=getPackageManager().queryIntentActivities(query,0);Map<String,String> map=new LinkedHashMap<>();for(ResolveInfo r:raw)if(!r.activityInfo.packageName.equals(getPackageName()))map.put(r.activityInfo.packageName,r.loadLabel(getPackageManager()).toString());
        List<String> pkgs=new ArrayList<>(map.keySet());pkgs.sort(Comparator.comparingInt((String p)->map.get(p).contains(preferred)?0:1).thenComparing(map::get));String[] names=new String[pkgs.size()];for(int i=0;i<names.length;i++)names[i]=map.get(pkgs.get(i));
        new AlertDialog.Builder(this).setTitle(title).setItems(names,(d,w)->choice.selected(pkgs.get(w),names[w])).setNegativeButton("取消",null).show();
    }
    private void chooseApp(String label,boolean fill){chooseInstalled("选择手机上安装的"+label,label,(pkg,name)->{getPreferences(0).edit().putString("app_"+label,pkg).apply();launch(label,pkg,fill);});}
    private void launch(String label,String pkg,boolean fill){try{
        Intent intent=getPackageManager().getLaunchIntentForPackage(pkg);if(intent==null){getPreferences(0).edit().remove("app_"+label).apply();toast("应用未安装，请重新选择");return;}
        if(getSystemService(KeyguardManager.class).isKeyguardLocked()){toast("请先解锁手机");return;}
        if(fill){JSONObject latest=Vault.latest(this);if(latest==null||batch==null||!latest.getString("id").equals(batch.getString("id"))){refresh();toast("资料已更新，请重新选择人员");return;}String after=getPreferences(0).getBoolean("afterEnabled",false)?getPreferences(0).getString("afterPkg",""):"";if(getPreferences(0).getBoolean("afterEnabled",false)&&after.isEmpty()){toast("请先选择完成后切换的应用，或关闭该功能");return;}if(FillService.isRunning()){toast("任务正在进行，可先暂停");return;}FlowRules.Platform platform=label.equals("票星球")?FlowRules.Platform.PIAOXINGQIU:FlowRules.Platform.MAOYAN;if(!FillService.start(pkg,platform,people,batch.getString("id"),after,new int[]{1000,2000,3000,5000}[getPreferences(0).getInt("stay",1)])){toast("辅助服务未启用");return;}}
        startActivity(intent);
    }catch(Exception e){FillService.cancel("启动失败，已停止");toast("无法打开目标应用，请重新选择");}}
}
