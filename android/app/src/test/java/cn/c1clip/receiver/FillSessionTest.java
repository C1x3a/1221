package cn.c1clip.receiver;
import org.junit.Test;
import static org.junit.Assert.*;
public class FillSessionTest {
    @Test public void clicksDoNotCountAsSaved(){FillSession s=new FillSession(0,false);s.submitted(1000);assertEquals(0,s.index);assertFalse(s.maySubmit(50000));s.saved();assertEquals(1,s.index);assertTrue(s.maySubmit(50000));}
    @Test public void retriesRequireExplicitFailureAndContinueAfterRecovery(){FillSession s=new FillSession(0,false);for(int i=0;i<6;i++){long now=(i+1)*2000;assertTrue(s.maySubmit(now));s.submitted(now);assertFalse(s.maySubmit(now+1500));s.failedExplicitly();assertFalse(s.maySubmit(now+100));}assertTrue(s.maySubmit(50000));assertEquals(0,s.index);}
    @Test public void uncertainSubmissionSurvivesResume(){FillSession s=new FillSession(2,true);assertFalse(s.maySubmit(999999));assertEquals(2,s.index);s.saved();assertEquals(3,s.index);assertTrue(s.maySubmit(999999));}
    @Test public void validatesMaskedIdentityInsteadOfTrustingNavigation(){assertTrue(FillSession.maskedIdMatches("身份证 000***********001","000000200001010001"));assertFalse(FillSession.maskedIdMatches("身份证 001***********002","000000200001010001"));assertFalse(FillSession.maskedIdMatches("添加成功","000000200001010001"));}
    @Test public void validationAndRateLimitsAreNeverRetried(){assertTrue(FillSession.permanentError("证件号码错误"));assertTrue(FillSession.permanentError("操作频繁，请稍后重试"));assertFalse(FillSession.transientError("操作频繁，请稍后重试"));assertTrue(FillSession.transientError("添加观演人信息失败"));assertFalse(FillSession.transientError("请输入姓名"));}
    @Test public void peopleAdvanceIndependently(){FillSession s=new FillSession(0,false);for(int i=0;i<3;i++){s.submitted(i*2000);assertEquals(i,s.index);s.saved();assertEquals(i+1,s.index);assertEquals(0,s.attempts);}}
}
