package cn.c1clip.receiver;

import java.util.regex.*;

/**
 * Persistent per-person transaction state.
 * Once a submit has happened, the session cannot return to filling unless an explicit failure is observed
 * or the user manually clears the uncertain result.
 */
public final class FillSession {
    public enum Phase { CHECKING_EXISTING, OPENING_FORM, FILLING, SUBMITTED_WAIT_VERIFY, VERIFYING_LIST }

    public static final int MAX_ATTEMPTS=3;
    public static final long LIST_SYNC_GRACE_MS=3000;
    public static final long FORM_RESULT_TIMEOUT_MS=12000;

    public int index,attempts=0;
    public boolean pending=false,retryable=false,successSignal=false,verificationStarted=false;
    public long sentAt=0;
    public Phase phase=Phase.CHECKING_EXISTING;

    public FillSession(int index,boolean pending){this(index,pending,false);}
    public FillSession(int index,boolean pending,boolean verificationStarted){
        this.index=index;this.pending=pending;this.verificationStarted=pending&&verificationStarted;
        this.phase=pending?(this.verificationStarted?Phase.VERIFYING_LIST:Phase.SUBMITTED_WAIT_VERIFY):Phase.CHECKING_EXISTING;
    }
    public FillSession(int index,String phaseName,int attempts,long sentAt){
        this.index=index;this.attempts=Math.max(0,attempts);this.sentAt=Math.max(0,sentAt);
        try{this.phase=Phase.valueOf(phaseName);}catch(Exception e){this.phase=Phase.CHECKING_EXISTING;}
        this.pending=this.phase==Phase.SUBMITTED_WAIT_VERIFY||this.phase==Phase.VERIFYING_LIST;
        this.verificationStarted=this.phase==Phase.VERIFYING_LIST;
    }

    public boolean needsVerification(){return pending||phase==Phase.SUBMITTED_WAIT_VERIFY||phase==Phase.VERIFYING_LIST;}
    public boolean mayFill(){return !needsVerification();}
    public boolean maySubmit(long now){return mayFill()&&(!retryable||now-sentAt>=1200);}

    public void checkingExisting(){if(!needsVerification())phase=Phase.CHECKING_EXISTING;}
    public void openingForm(){if(!needsVerification())phase=Phase.OPENING_FORM;}
    public void filling(){if(!needsVerification())phase=Phase.FILLING;}

    public void submitted(long now){
        pending=true;retryable=false;successSignal=false;verificationStarted=false;
        phase=Phase.SUBMITTED_WAIT_VERIFY;attempts++;sentAt=now;
    }
    public void startedVerification(){
        if(needsVerification()){pending=true;verificationStarted=true;phase=Phase.VERIFYING_LIST;}
    }
    public void failedExplicitly(){
        if(needsVerification()){
            pending=false;retryable=true;verificationStarted=false;successSignal=false;phase=Phase.CHECKING_EXISTING;
        }
    }
    public void manuallyAllowRetry(){
        pending=false;retryable=true;verificationStarted=false;successSignal=false;phase=Phase.CHECKING_EXISTING;
    }
    public void saved(){
        index++;attempts=0;pending=false;retryable=false;successSignal=false;verificationStarted=false;sentAt=0;phase=Phase.CHECKING_EXISTING;
    }

    public static boolean shouldWaitForListSync(boolean pending,long firstSeen,long now){return pending&&firstSeen>0&&now-firstSeen<LIST_SYNC_GRACE_MS;}
    public static boolean shouldPauseUncertainForm(boolean pending,long sentAt,long now){return pending&&(sentAt==0||now-sentAt>=FORM_RESULT_TIMEOUT_MS);}
    public static boolean permanentError(String text){return text.matches("(?s).*(验证码|滑块|操作频繁|请求频繁|证件.*错误|证件.*有误|身份证.*错误|身份证.*有误|身份.*不匹配|姓名.*有误|姓名.*不匹配|校验失败|人数上限|数量上限|已达上限|请先登录|登录失效).*");}
    public static boolean transientError(String text){return !permanentError(text)&&text.matches("(?s).*(添加.*失败|保存.*失败|网络异常|网络错误|服务繁忙|稍后重试).*");}

    public static boolean maskedIdMatches(String displayed,String id){
        String source=displayed==null?"":displayed.replaceAll("\\s+","");
        String wanted=id==null?"":id.replaceAll("\\s+","");
        if(source.isEmpty()||wanted.isEmpty())return false;
        String cleaned=source.replace("身份证","").replace("证件号码","").replace("证件号","").replace(":","").replace("：","");
        if(cleaned.equalsIgnoreCase(wanted)||source.equalsIgnoreCase(wanted))return true;
        Matcher m=Pattern.compile("([0-9]{2,8})[*•●]{2,}([0-9Xx]{2,8})").matcher(source);
        while(m.find())if(wanted.startsWith(m.group(1))&&wanted.toUpperCase().endsWith(m.group(2).toUpperCase()))return true;
        return false;
    }

    public static boolean maskedNameMatches(String displayed,String name){
        String shown=FlowRules.norm(displayed),wanted=FlowRules.norm(name);
        if(shown.equals(wanted))return true;
        if(shown.contains(wanted)&&wanted.length()>=2)return true;
        Matcher m=Pattern.compile("^[*•●]+(.+)$").matcher(shown);
        if(!m.find())return false;
        String visible=m.group(1);
        return !visible.isEmpty()&&visible.length()<=wanted.length()&&wanted.endsWith(visible);
    }
}
