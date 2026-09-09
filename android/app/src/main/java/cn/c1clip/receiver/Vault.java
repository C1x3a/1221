package cn.c1clip.receiver;

import android.content.Context;
import android.security.keystore.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class Vault {
    private static final String ALIAS="c1clip.local.v1";
    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        return (SecretKey)ks.getKey(ALIAS,null);
    }
    public static synchronized JSONObject read(Context c) throws Exception {
        String raw=c.getSharedPreferences("vault",0).getString("sealed",null);if(raw==null)return new JSONObject();
        JSONObject p=new JSONObject(raw);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Protocol.decode(p.getString("iv"))));
        return new JSONObject(new String(cipher.doFinal(Protocol.decode(p.getString("data"))),StandardCharsets.UTF_8));
    }
    public static synchronized void write(Context c,JSONObject state) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        JSONObject p=new JSONObject().put("iv",Protocol.b64(cipher.getIV())).put("data",Protocol.b64(cipher.doFinal(state.toString().getBytes(StandardCharsets.UTF_8))));
        if(!c.getSharedPreferences("vault",0).edit().putString("sealed",p.toString()).commit())throw new Exception("保存失败，请检查存储空间");
    }
    public static synchronized JSONObject latest(Context c) throws Exception {
        JSONObject s=read(c),b=s.optJSONObject("batch");
        if(b!=null&&!Protocol.validBatch(b,System.currentTimeMillis())){s.remove("batch");s.remove("receipt");write(c,s);return null;}return b;
    }
    public static synchronized void savePair(Context c,JSONObject p) throws Exception {
        JSONObject s=read(c);JSONObject old=s.optJSONObject("pair");
        if(old==null||!old.optString("key").equals(p.optString("key"))||!old.optString("host").equals(p.optString("host"))||!old.optString("device").equals(p.optString("device"))){s.remove("batch");s.remove("receipt");s.remove("watermark");}
        s.put("pair",p);write(c,s);
    }
    public static synchronized boolean receive(Context c,JSONObject b) throws Exception {
        JSONObject s=read(c);if(b.getLong("createdAt")<=s.optLong("watermark",0))return false;
        s.put("batch",b).put("watermark",b.getLong("createdAt")).put("receipt",new JSONObject().put("t","receipt").put("id",b.getString("id")).put("status","received").put("copied",0));write(c,s);return true;
    }
    public static synchronized void receipt(Context c,String id,String status,int count,String error) throws Exception {
        JSONObject s=read(c),b=s.optJSONObject("batch");if(b==null||!b.optString("id").equals(id))return;
        s.put("receipt",new JSONObject().put("t","receipt").put("id",id).put("status",status).put("copied",count).put("error",error));write(c,s);
    }
}
