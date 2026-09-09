package cn.c1clip.receiver;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class FlowRulesTest {
    private Set<String> set(String... values){Set<String>s=new HashSet<>();for(String v:values)s.add(FlowRules.norm(v));return s;}
    @Test public void detectsMaoyanListWithDecoratedButton(){assertEquals(FlowRules.Step.LIST,FlowRules.detect(FlowRules.Platform.MAOYAN,set("观演人信息","+ 添加 / 修改观演人信息")));}
    @Test public void detectsMaoyanForm(){assertEquals(FlowRules.Step.FORM,FlowRules.detect(FlowRules.Platform.MAOYAN,set("添加观演人信息","姓名","证件号","身份证")));}
    @Test public void detectsPlanetScreens(){assertEquals(FlowRules.Step.PROFILE,FlowRules.detect(FlowRules.Platform.PIAOXINGQIU,set("我的抢票","观演 / 赛人")));assertEquals(FlowRules.Step.LIST,FlowRules.detect(FlowRules.Platform.PIAOXINGQIU,set("观演/赛人","新增观演/赛人")));assertEquals(FlowRules.Step.FORM,FlowRules.detect(FlowRules.Platform.PIAOXINGQIU,set("新增观演/赛人","姓名","证件号码")));}
    @Test public void detectsAgreementOnlyForm(){assertEquals(FlowRules.Step.FORM,FlowRules.detect(FlowRules.Platform.MAOYAN,set("我已阅读并同意《实名说明》","确定")));assertEquals(FlowRules.Step.FORM,FlowRules.detect(FlowRules.Platform.PIAOXINGQIU,set("请阅读并同意《敏感个人信息授权书》","保存")));}
    @Test public void formStageOnlyAdvancesMissingWork(){assertEquals(FlowRules.FormStage.NAME,FlowRules.formStage(false,false,false,false));assertEquals(FlowRules.FormStage.ID,FlowRules.formStage(true,false,false,false));assertEquals(FlowRules.FormStage.AGREEMENT,FlowRules.formStage(true,true,false,false));assertEquals(FlowRules.FormStage.CONFIRM,FlowRules.formStage(true,true,true,false));assertEquals(FlowRules.FormStage.SAVING,FlowRules.formStage(true,true,true,true));}
    @Test public void detectsPlanetConsentDialog(){assertTrue(FlowRules.planetConsentDialog(set("已阅读并同意《敏感个人信息授权书》和《儿童敏感个人信息授权书》中的全部条款","不同意","同意")));assertFalse(FlowRules.planetConsentDialog(set("请阅读并同意《敏感个人信息授权书》","保存")));}
}
