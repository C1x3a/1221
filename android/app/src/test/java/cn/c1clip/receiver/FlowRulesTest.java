package cn.c1clip.receiver;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class FlowRulesTest {
    private Set<String> set(String... values){Set<String>s=new HashSet<>();for(String v:values)s.add(FlowRules.norm(v));return s;}
    @Test public void detectsMaoyanListWithDecoratedButton(){assertEquals(FlowRules.Step.LIST,FlowRules.detect(FlowRules.Platform.MAOYAN,set("观演人信息","+ 添加 / 修改观演人信息")));}
    @Test public void detectsMaoyanForm(){assertEquals(FlowRules.Step.FORM,FlowRules.detect(FlowRules.Platform.MAOYAN,set("添加观演人信息","姓名","证件号","身份证")));}
    @Test public void detectsPlanetScreens(){assertEquals(FlowRules.Step.PROFILE,FlowRules.detect(FlowRules.Platform.PIAOXINGQIU,set("我的抢票","观演 / 赛人")));assertEquals(FlowRules.Step.LIST,FlowRules.detect(FlowRules.Platform.PIAOXINGQIU,set("观演/赛人","新增观演/赛人")));assertEquals(FlowRules.Step.FORM,FlowRules.detect(FlowRules.Platform.PIAOXINGQIU,set("新增观演/赛人","姓名","证件号码")));}
}
