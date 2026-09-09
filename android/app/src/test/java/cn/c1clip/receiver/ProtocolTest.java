package cn.c1clip.receiver;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
public class ProtocolTest {
    @Test public void decryptsRealWebProtocolFixture() throws Exception {
        try(java.io.InputStream stream=getClass().getResourceAsStream("/web-message.json")){
            JSONObject fixture=new JSONObject(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            JSONObject data=Protocol.unseal(fixture.getString("key"),fixture.getJSONObject("packet"));
            assertEquals("interop-session",data.getString("session"));
            assertEquals("测试姓名",data.getJSONObject("batch").getJSONArray("items").getString(0));
        }
    }

    @Test public void encryptionRejectsWrongKeysAndTampering() throws Exception {
        String key=Protocol.token(32);JSONObject payload=new JSONObject().put("t","hello").put("session","test-session"),packet=Protocol.seal(key,payload);
        assertEquals("hello",Protocol.unseal(key,packet).getString("t"));assertThrows(Exception.class,()->Protocol.unseal(Protocol.token(32),packet));packet.put("data",Protocol.token(30));assertThrows(Exception.class,()->Protocol.unseal(key,packet));
    }
    @Test public void namesAndIdsStaySeparateAndGroupInPairs() throws Exception {
        JSONArray a=new JSONArray().put("测试甲").put("000000200001010001").put("测试乙").put("000000200001010002");assertEquals(2,Protocol.people(a).size());assertEquals("测试乙",Protocol.people(a).get(1)[0]);assertEquals(4,a.length());assertThrows(Exception.class,()->Protocol.people(new JSONArray().put("only one")));
    }
    @Test public void invalidOrExpiredMessagesAreRejected() throws Exception {
        long now=System.currentTimeMillis();JSONObject b=new JSONObject().put("id",Protocol.token(18)).put("createdAt",now).put("items",new JSONArray().put("姓名").put("证件号"));assertTrue(Protocol.validBatch(b,now));b.put("createdAt",now-Protocol.TTL);assertFalse(Protocol.validBatch(b,now));b.put("createdAt",now).put("items",new JSONArray().put(" "));assertFalse(Protocol.validBatch(b,now));
    }
    @Test public void pairAcceptsExistingWebLinkButRejectsInsecureTransport() throws Exception {
        JSONObject p=new JSONObject().put("v",2).put("host","c1clip-"+Protocol.token(18)).put("device","d"+Protocol.token(12)).put("key",Protocol.token(32)).put("name","01").put("broker",new JSONObject().put("urls",new JSONArray().put("wss://example.org/mqtt")).put("username","").put("password",""));
        String code=Protocol.b64(p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals("01",Protocol.pair("https://example.org/#pair="+code).getString("name"));p.getJSONObject("broker").put("urls",new JSONArray().put("ws://example.org/mqtt"));String bad=Protocol.b64(p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThrows(Exception.class,()->Protocol.pair(bad));
    }
    @Test public void onlyRecognizesTheFourSuppliedPages(){
        assertEquals(FlowRules.Step.HOME,FlowRules.detect(Set.of("我的","首页","电影/影院")));
        assertEquals(FlowRules.Step.PROFILE,FlowRules.detect(Set.of("我的订单","观演人信息")));
        assertEquals(FlowRules.Step.LIST,FlowRules.detect(Set.of("观演人信息","+添加/修改观演人信息")));
        assertEquals(FlowRules.Step.FORM,FlowRules.detect(Set.of("添加观演人信息","姓名","证件号","身份证")));
        assertEquals(FlowRules.Step.UNKNOWN,FlowRules.detect(Set.of("立即购买","提交订单","姓名","证件号")));
    }
}
