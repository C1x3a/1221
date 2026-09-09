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
    private LinearLayout body,contents;private TextView network,fillStatus,copyStatus;private EditText pairInput;private Spinner persons;
    private JSONObject batch;private List<String[]> people=new ArrayList<>();private String shown="";private boolean copying=false,resumed=false;private int copied=0;private String copyId="";
    private int blue=Color.rgb(21,93,251);
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,24,24,48);scroll.addView(body);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(244,247,253));setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{body.setPadding(24,24+insets.getSystemWindowInsetTop(),24,48+insets.getSystemWindowInsetBottom());return insets;});
        heading("多机接收 · 升级版",25);text("测试版 0.1 · 息屏接收 / 独立复制 / 猫眼辅助填写");network=text(ReceiverService.status);
        button("开始后台接收",()->startReceive());button("停止后台接收",()->{FillService.cancel("已停止");stopService(new Intent(this,ReceiverService.class));});
        heading("连接电脑",20);text("在原电脑网页找到这部手机 → 配对 → 复制连接链接，粘贴到下面。每部手机使用自己的专用链接。APP 启用后，请关闭该手机的旧接收网页，避免重复接收。");
        pairInput=new EditText(this);pairInput.setHint("粘贴这部手机的配对链接");pairInput.setMinLines(2);pairInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);pairInput.setAutofillHints((String[])null);body.addView(pairInput);
        button("保存配对并接收",()->{try{JSONObject pair=Protocol.pair(pairInput.getText().toString());FillService.cancel("配对已更新");stopCopy("配对已更新");stopService(new Intent(this,ReceiverService.class));Vault.savePair(this,pair);pairInput.setText("");shown="";handler.postDelayed(this::startReceive,500);refresh();toast("已保存配对："+pair.getString("name"));}catch(Exception e){toast("配对失败："+e.getMessage());}});
        heading("后台运行设置",20);text("首次启用请允许通知，并允许此 APP 不受电池优化限制。小米系统中再检查后台自启动与省电设置。通知栏可随时停止接收；运行期间会增加耗电，系统强行停止或断网时仍可能离线。");
        button("允许持续后台联网",()->{Intent i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()));safeStart(i);});
        button("打开本应用系统设置",()->safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        heading("最新接收内容",20);contents=new LinearLayout(this);contents.setOrientation(LinearLayout.VERTICAL);body.addView(contents);copyStatus=text("姓名和身份证各占一条，默认间隔 1.5 秒");
        EditText keyboard=new EditText(this);keyboard.setHint("点这里唤起输入法，再点击逐条复制");body.addView(keyboard);
        button("一键逐条复制",this::copyAll);button("停止复制",()->stopCopy("已停止复制"));
        button("输入法历史中已全部出现",()->{try{if(batch==null)return;JSONObject receipt=Vault.read(this).optJSONObject("receipt");if(receipt==null||receipt.optInt("copied")!=batch.getJSONArray("items").length()){toast("请先完成全部复制并检查输入法历史");return;}Vault.receipt(this,batch.getString("id"),"confirmed",receipt.getInt("copied"),"");sendReceipt();toast("确认已发送给电脑");}catch(Exception e){toast("确认保存失败");}});
        button("清空本机当前内容",()->{stopCopy("已清空");FillService.cancel("内容已清空");try{synchronized(Vault.class){JSONObject s=Vault.read(this),b=s.optJSONObject("batch");if(b!=null){s.put("receipt",new JSONObject().put("t","receipt").put("id",b.getString("id")).put("status","deleted").put("copied",0));s.remove("batch");Vault.write(this,s);}}shown="";refresh();sendReceipt();}catch(Exception e){toast("清空失败");}});
        heading("应用与辅助填写",20);text("首次点击会选择手机上已安装的应用。猫眼按你提供的四张页面适配；大麦、票星球目前支持打开应用，填写流程待页面适配。操作页面时必须解锁并保持目标应用在前台。");
        persons=new Spinner(this);body.addView(persons);fillStatus=text(FillService.status);
        for(String label:new String[]{"猫眼","大麦","票星球"})button("打开"+label,()->target(label,false));
        button("猫眼辅助填写 · 当前所选人员",()->target("猫眼",true));button("停止辅助填写",()->FillService.cancel("已手动停止"));
        button("启用辅助填写权限",()->new AlertDialog.Builder(this).setTitle("辅助填写的权限说明").setMessage("此功能需你手动开启安卓无障碍服务。仅在你启动任务后，读取选定猫眼应用的页面控件并填写所选资料；不会自动勾选实名协议或点击确定，不上传页面内容。遇到锁屏、新资料或未知页面将停止。允许后前往系统设置。侧载应用若显示‘受限设置’，请按系统提供的说明处理。").setPositiveButton("前往系统设置",(d,w)->safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("取消",null).show());
        button("重新选择三款目标应用",()->{getPreferences(0).edit().remove("app_猫眼").remove("app_大麦").remove("app_票星球").apply();toast("下次打开时会重新选择");});
        text("多人的资料请逐人选择并填写，核对后在猫眼保存，再回来选择下一人。图中页面以外的流程不会自动继续。");refresh();
    }
    private void startReceive(){try{if(Vault.read(this).optJSONObject("pair")==null){toast("请先粘贴电脑配对链接");return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);startForegroundService(new Intent(this,ReceiverService.class));toast("后台接收已启动");}catch(Exception e){toast("启动失败，请重新配对或检查系统设置");}}
    private TextView text(String value){TextView t=new TextView(this);t.setText(value);t.setTextSize(15);t.setTextColor(Color.rgb(40,56,77));t.setPadding(4,10,4,10);body.addView(t);return t;}
    private void heading(String label,int size){TextView t=text(label);t.setTextSize(size);t.setTypeface(null,1);t.setPadding(4,26,4,10);}
    private void button(String label,Runnable task){Button b=new Button(this);b.setText(label);b.setTextColor(blue);b.setAllCaps(false);b.setOnClickListener(v->task.run());body.addView(b);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void safeStart(Intent i){try{startActivity(i);}catch(Exception e){toast("无法打开，请在手机设置里手动查找该选项");}}
    @Override protected void onResume(){super.onResume();resumed=true;handler.post(refreshTick);}
    @Override protected void onPause(){super.onPause();resumed=false;handler.removeCallbacks(refreshTick);stopCopy("页面离开前台，已停止复制");}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus)stopCopy("页面失去焦点，已停止复制");}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private final Runnable refreshTick=new Runnable(){public void run(){refresh();if(resumed)handler.postDelayed(this,1200);}};
    private void refresh(){try{
        JSONObject state=Vault.read(this),pair=state.optJSONObject("pair");network.setText((pair==null?"未配对":pair.optString("name"))+"\n"+ReceiverService.status);fillStatus.setText(FillService.status);
        JSONObject next=Vault.latest(this);String nextId=next==null?"empty":next.optString("id");if(nextId.equals(shown))return;
        stopCopy("收到最新内容，已停止旧内容复制");batch=next;shown=nextId;contents.removeAllViews();people=new ArrayList<>();
        if(batch==null){TextView empty=new TextView(this);empty.setText("等待电脑发送最新信息…");contents.addView(empty);}
        else{JSONArray items=batch.getJSONArray("items");for(int i=0;i<items.length();i++){TextView t=new TextView(this);t.setText((i+1)+"  "+items.getString(i));t.setTextSize(17);t.setTextIsSelectable(true);t.setPadding(12,14,12,14);t.setBackgroundColor(Color.WHITE);contents.addView(t);}try{people=Protocol.people(items);}catch(Exception e){copyStatus.setText(e.getMessage());}}
        List<String> labels=new ArrayList<>();for(int i=0;i<people.size();i++)labels.add("第 "+(i+1)+" 人 · "+people.get(i)[0]);if(labels.isEmpty())labels.add("暂无可填写的姓名＋身份证组合");persons.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
    }catch(Exception e){network.setText("读取本机数据失败，请重新打开应用；不要清除数据以免丢失配对");}}
    private void sendReceipt(){try{if(!ReceiverService.status.equals("未启动接收")&&!ReceiverService.status.equals("接收已停止"))startService(new Intent(this,ReceiverService.class).setAction("RECEIPT"));}catch(Exception ignored){}}
    private void copyAll(){if(batch==null||copying)return;try{copying=true;copied=0;copyId=batch.getString("id");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);copyNext();}catch(Exception e){stopCopy("复制失败");}}
    private void copyNext(){if(!copying)return;try{
        JSONObject latest=Vault.latest(this);if(!resumed||!hasWindowFocus()||latest==null||!copyId.equals(latest.optString("id"))){stopCopy("页面或内容已改变，请重新开始");return;}
        JSONArray items=latest.getJSONArray("items");getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("独立信息",items.getString(copied)));copied++;copyStatus.setText("已复制 "+copied+" / "+items.length()+" 条，请检查输入法历史");
        boolean done=copied==items.length();Vault.receipt(this,copyId,done?"copied":"copying",copied,"");sendReceipt();if(done){copying=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}else handler.postDelayed(this::copyNext,1500);
    }catch(Exception e){stopCopy("复制失败，请回到前台重试");}}
    private void stopCopy(String reason){if(!copying)return;copying=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);copyStatus.setText(reason);try{Vault.receipt(this,copyId,"error",copied,reason);sendReceipt();}catch(Exception ignored){}}
    private void target(String label,boolean fill){
        if(fill){if(people.isEmpty()){toast("请先接收并核对姓名和身份证");return;}if(!FillService.available()){toast("请先点击‘启用辅助填写权限’");return;}}
        String pkg=getPreferences(0).getString("app_"+label,"");if(pkg.isEmpty()){chooseApp(label,fill);return;}launch(label,pkg,fill);
    }
    private void chooseApp(String label,boolean fill){
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> raw=getPackageManager().queryIntentActivities(query,0);Map<String,String> map=new LinkedHashMap<>();for(ResolveInfo r:raw)if(!r.activityInfo.packageName.equals(getPackageName()))map.put(r.activityInfo.packageName,r.loadLabel(getPackageManager()).toString());
        List<String> pkgs=new ArrayList<>(map.keySet());pkgs.sort(Comparator.comparingInt((String p)->map.get(p).contains(label)?0:1).thenComparing(map::get));String[] names=new String[pkgs.size()];for(int i=0;i<names.length;i++)names[i]=map.get(pkgs.get(i));
        new AlertDialog.Builder(this).setTitle("选择手机上安装的"+label).setItems(names,(d,w)->{String p=pkgs.get(w);new AlertDialog.Builder(this).setTitle("确认目标应用").setMessage("将‘"+names[w]+"’设为"+label+"入口？").setPositiveButton("使用此应用",(dd,ww)->{getPreferences(0).edit().putString("app_"+label,p).apply();launch(label,p,fill);}).setNegativeButton("取消",null).show();}).setNegativeButton("取消",null).show();
    }
    private void launch(String label,String pkg,boolean fill){try{
        Intent intent=getPackageManager().getLaunchIntentForPackage(pkg);if(intent==null){getPreferences(0).edit().remove("app_"+label).apply();toast("应用未安装，请重新选择");return;}
        if(getSystemService(KeyguardManager.class).isKeyguardLocked()){toast("请先解锁手机");return;}
        if(fill){JSONObject latest=Vault.latest(this);if(latest==null||batch==null||!latest.getString("id").equals(batch.getString("id"))){refresh();toast("资料已更新，请重新选择人员");return;}int index=persons.getSelectedItemPosition();String[] person=people.get(index);if(!FillService.start(pkg,person[0],person[1],batch.getString("id"))){toast("辅助服务未启用");return;}}
        startActivity(intent);
    }catch(Exception e){FillService.cancel("启动失败，已停止");toast("无法打开目标应用，请重新选择");}}
}
