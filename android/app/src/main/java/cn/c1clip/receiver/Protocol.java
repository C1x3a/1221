package cn.c1clip.receiver;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public final class Protocol {
    public static final long TTL = 12 * 60 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    static byte[] bytes(int n) { byte[] b=new byte[n]; RANDOM.nextBytes(b); return b; }
    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] decode(String s) { if(!s.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("连接码格式错误"); return Base64.getUrlDecoder().decode(s); }
    public static String token(int n) { return b64(bytes(n)); }
    public static JSONObject pair(String input) throws Exception {
        String s=input.trim(); if(s.contains("#pair=")) s=s.substring(s.indexOf("#pair=")+6);
        s=s.split("[\\s&]",2)[0]; if(s.length()>4000) throw new Exception("配对链接过长");
        JSONObject p=new JSONObject(new String(decode(s), StandardCharsets.UTF_8));
        if(p.getInt("v")!=2 || !p.getString("host").matches("c1clip-[A-Za-z0-9_-]{20,50}") || !p.getString("device").matches("d[A-Za-z0-9_-]{10,40}") || decode(p.getString("key")).length!=32 || p.getString("name").trim().isEmpty() || p.getString("name").length()>60) throw new Exception("请使用当前电脑版生成的配对链接");
        JSONObject broker=p.getJSONObject("broker"); JSONArray urls=broker.getJSONArray("urls");
        if(urls.length()<1||urls.length()>3||broker.getString("username").length()>128||broker.getString("password").length()>256) throw new Exception("连接设置无效");
        for(int i=0;i<urls.length();i++){String url=urls.getString(i);URI u=new URI(url);if(url.length()>300||!"wss".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null) throw new Exception("仅支持安全 WSS 连接");}
        return p;
    }
    public static JSONObject seal(String key, JSONObject value) throws Exception {
        byte[] iv=bytes(12); Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(decode(key),"AES"),new GCMParameterSpec(128,iv));
        return new JSONObject().put("v",1).put("iv",b64(iv)).put("data",b64(c.doFinal(value.toString().getBytes(StandardCharsets.UTF_8))));
    }
    public static JSONObject unseal(String key, JSONObject packet) throws Exception {
        if(packet.getInt("v")!=1||packet.getString("data").length()>100000) throw new Exception("消息格式错误");
        byte[] iv=decode(packet.getString("iv"));if(iv.length!=12)throw new Exception("消息格式错误");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(decode(key),"AES"),new GCMParameterSpec(128,iv));
        return new JSONObject(new String(c.doFinal(decode(packet.getString("data"))),StandardCharsets.UTF_8));
    }
    public static boolean validBatch(JSONObject b,long now) {
        try {
            long time=b.getLong("createdAt");JSONArray a=b.getJSONArray("items");
            if(!b.getString("id").matches("[A-Za-z0-9_-]{12,64}")||time<=now-TTL||time>=now+60000||a.length()<1||a.length()>240) return false;
            int total=0;for(int i=0;i<a.length();i++){Object item=a.get(i);if(!(item instanceof String))return false;String s=(String)item;if(s.trim().isEmpty()||s.length()>4000)return false;total+=s.length();}return total<=16000;
        }catch(Exception e){return false;}
    }
    public static List<String[]> people(JSONArray items) throws Exception {
        if(items.length()%2!=0)throw new Exception("辅助填写需要姓名、身份证依次各占一条，请回电脑核对");
        List<String[]> result=new ArrayList<>();for(int i=0;i<items.length();i+=2){String name=items.getString(i).trim(),id=items.getString(i+1).trim().toUpperCase(Locale.ROOT);if(name.isEmpty()||name.length()>60||!id.matches("(?:[0-9]{17}[0-9X]|[0-9]{15})"))throw new Exception("姓名和证件号的顺序或格式不正确，请先核对");result.add(new String[]{name,id});}return result;
    }
}
