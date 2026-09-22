package cn.c1clip.receiver;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PlatformPageDetectorTest {
    private PlatformPageDetector.Snapshot s(int editable,boolean masked,boolean add,boolean confirm,String... values){
        Set<String> set=new LinkedHashSet<>();for(String v:values)set.add(FlowRules.norm(v));
        return new PlatformPageDetector.Snapshot(set,editable,masked,add,confirm);
    }

    @Test public void maoyanRealListIsNotMisreadAsProfile(){
        PlatformPageDetector.Result r=PlatformPageDetector.detect(FlowRules.Platform.MAOYAN,
                s(0,true,true,false,"常用信息","观演人信息","+ 添加 / 修改观演人信息","*钰音","身份证 360***********027"));
        assertEquals(PlatformPageDetector.Page.PERSON_LIST,r.page);
    }

    @Test public void maoyanFormWinsOverAddPersonTitle(){
        PlatformPageDetector.Result r=PlatformPageDetector.detect(FlowRules.Platform.MAOYAN,
                s(2,false,false,true,"添加观演人信息","姓名","证件号","身份证","我已阅读并同意","确定"));
        assertEquals(PlatformPageDetector.Page.ADD_FORM,r.page);
    }

    @Test public void planetListAndFormWithSameTitleStayDistinct(){
        assertEquals(PlatformPageDetector.Page.PERSON_LIST,
                PlatformPageDetector.detect(FlowRules.Platform.PIAOXINGQIU,
                        s(0,true,true,false,"观演/赛人","新增观演/赛人","*源源","身份证 360***********048")).page);
        assertEquals(PlatformPageDetector.Page.ADD_FORM,
                PlatformPageDetector.detect(FlowRules.Platform.PIAOXINGQIU,
                        s(2,false,false,true,"新增观演/赛人","姓名","证件号码","请阅读并同意","保存")).page);
    }

    @Test public void planetConsentDialogIsExplicit(){
        assertEquals(PlatformPageDetector.Page.CONSENT_DIALOG,
                PlatformPageDetector.detect(FlowRules.Platform.PIAOXINGQIU,
                        s(0,false,false,false,"敏感个人信息授权书","不同意","同意")).page);
    }

    @Test public void checkoutLikeScreenDoesNotBecomeForm(){
        assertEquals(PlatformPageDetector.Page.UNKNOWN,
                PlatformPageDetector.detect(FlowRules.Platform.MAOYAN,
                        s(0,false,false,false,"立即购买","提交订单","姓名","证件号")).page);
    }

    @Test public void weakTextDoesNotAuthorizeClicks(){
        assertEquals(PlatformPageDetector.Page.UNKNOWN,
                PlatformPageDetector.detect(FlowRules.Platform.PIAOXINGQIU,
                        s(0,false,false,false,"新增观演/赛人")).page);
    }
}
