package cn.c1clip.receiver;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public final class ReceiverService extends Service {
    public static final String UPDATE="cn.c1clip.receiver.UPDATE";
    private static final String CHANNEL="receive";
    public static volatile String status="未启动接收";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<MqttAsyncClient> clients=new CopyOnWriteArrayList<>();
    private JSONObject pair; private String session; private long seen=0,wakeAt=0; private boolean trusted=false,stopped=false;
    private PowerManager.WakeLock wake;
    private void update(String text){status=text;getSystemService(NotificationManager.class).notify(10,notification());sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ReceiverService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("多机接收 · 后台运行").setContentText(status).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,"停止接收及辅助填写",stop).build()).build();
    }
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"后台接收状态",NotificationManager.IMPORTANCE_LOW));startForeground(10,notification());}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"STOP".equals(intent.getAction())){FillService.cancel("已停止");stopSelf();return START_NOT_STICKY;}
        if(pair==null){try{pair=Vault.read(this).optJSONObject("pair");if(pair==null)throw new Exception();session=Protocol.token(18);wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"c1clip:receive");wake.setReferenceCounted(false);renewWake();worker.execute(this::connect);main.post(tick);}catch(Exception e){status="请先配对";stopSelf();return START_NOT_STICKY;}}
        if(intent!=null&&"RECEIPT".equals(intent.getAction()))sendReceipt();
        return START_STICKY;
    }
    private void renewWake(){if(wake!=null){if(wake.isHeld())wake.release();wake.acquire(10*60*1000L);wakeAt=SystemClock.elapsedRealtime();}}
    private String base(){return "c1clip/v2/"+pair.optString("host")+"/device/"+pair.optString("device");}
    private final Runnable tick=new Runnable(){@Override public void run(){if(stopped)return;if(SystemClock.elapsedRealtime()-wakeAt>5*60*1000L)renewWake();try{Vault.latest(ReceiverService.this);}catch(Exception ignored){}if(System.currentTimeMillis()-seen>35000){trusted=false;update("配对已保留，等待电脑或网络连接");}send(new JSONObject(),trusted?"ping":"hello");main.postDelayed(this,8000);}};
    private void connect(){
        try{
            JSONObject broker=pair.getJSONObject("broker");JSONArray urls=broker.getJSONArray("urls");
            for(int i=0;i<urls.length()&&!stopped;i++){
                MqttAsyncClient client=new MqttAsyncClient(urls.getString(i),"c1clip_"+Protocol.token(12),new MemoryPersistence());clients.add(client);
                client.setCallback(new MqttCallbackExtended(){
                    @Override public void connectComplete(boolean reconnect,String uri){try{client.subscribe(base()+"/in",1,null,new IMqttActionListener(){public void onSuccess(IMqttToken t){send(new JSONObject(),"hello");}public void onFailure(IMqttToken t,Throwable e){main.post(()->update("订阅失败，正在重试"));}});}catch(Exception ignored){}}
                    @Override public void connectionLost(Throwable cause){main.post(()->{if(!stopped){trusted=false;update("网络中断，自动重连中");}});}
                    @Override public void messageArrived(String topic,MqttMessage message){if(message.getPayload().length>110000||stopped)return;byte[] bytes=message.getPayload().clone();try{worker.execute(()->decode(topic,bytes));}catch(RejectedExecutionException ignored){}}
                    @Override public void deliveryComplete(IMqttDeliveryToken t){}
                });
                MqttConnectOptions options=new MqttConnectOptions();options.setAutomaticReconnect(true);options.setCleanSession(true);options.setConnectionTimeout(12);options.setKeepAliveInterval(25);options.setHttpsHostnameVerificationEnabled(true);
                if(!broker.getString("username").isEmpty())options.setUserName(broker.getString("username"));if(!broker.getString("password").isEmpty())options.setPassword(broker.getString("password").toCharArray());
                // Paho retries established connections; an initial failure needs an explicit retry.
                open(client,options);
            }
        }catch(Exception e){main.post(()->update("连接设置无效，请重新配对"));}
    }
    private void open(MqttAsyncClient client,MqttConnectOptions options){if(stopped)return;try{client.connect(options,null,new IMqttActionListener(){public void onSuccess(IMqttToken t){}public void onFailure(IMqttToken t,Throwable e){main.postDelayed(()->{if(!stopped&&!client.isConnected())open(client,options);},8000);}});}catch(Exception e){main.postDelayed(()->{if(!stopped&&!client.isConnected())open(client,options);},8000);}}
    private void decode(String topic,byte[] bytes){
        try{if(!topic.equals(base()+"/in")||stopped)return;JSONObject value=Protocol.unseal(pair.getString("key"),new JSONObject(new String(bytes,StandardCharsets.UTF_8)));main.post(()->accept(value));}catch(Exception ignored){}
    }
    private void accept(JSONObject value){
        if(stopped||!session.equals(value.optString("session")))return;
        String type=value.optString("t");seen=System.currentTimeMillis();
        if(type.equals("ready")||type.equals("pong")){trusted=true;update("已连接电脑 · 息屏接收中");if(type.equals("ready"))sendReceipt();return;}
        if(!trusted)return;
        if(type.equals("batch"))try{
            JSONObject b=value.getJSONObject("batch");if(!Protocol.validBatch(b,System.currentTimeMillis()))return;
            if(Vault.receive(this,b)){FillService.cancel("收到新资料，旧填写任务已停止");update("已接收 "+b.getJSONArray("items").length()+" 条最新信息");}
            sendReceipt();
        }catch(Exception e){update("内容保存失败，请检查存储空间");}
    }
    private void sendReceipt(){try{JSONObject receipt=Vault.read(this).optJSONObject("receipt");if(receipt!=null)send(receipt,null);}catch(Exception ignored){}}
    private void send(JSONObject payload,String type){
        if(stopped||pair==null)return;
        try{JSONObject p=new JSONObject(payload.toString());if(type!=null)p.put("t",type);p.put("session",session);worker.execute(()->{
            try{byte[] bytes=Protocol.seal(pair.getString("key"),p).toString().getBytes(StandardCharsets.UTF_8);for(MqttAsyncClient c:clients)if(!stopped&&c.isConnected())try{c.publish(base()+"/out",bytes,1,false);}catch(Exception ignored){}}catch(Exception ignored){}
        });}catch(Exception ignored){}
    }
    @Override public void onDestroy(){stopped=true;main.removeCallbacksAndMessages(null);if(wake!=null&&wake.isHeld())wake.release();worker.shutdownNow();for(MqttAsyncClient c:clients)try{c.disconnectForcibly(0,0,false);c.close(true);}catch(Exception ignored){}clients.clear();status="接收已停止";sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
