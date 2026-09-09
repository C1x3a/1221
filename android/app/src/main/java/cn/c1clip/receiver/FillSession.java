package cn.c1clip.receiver;
/** Per-person submission guard: never retry a request whose outcome is unknown. */
public final class FillSession {
    public static final int MAX_ATTEMPTS=6;
    public int index,attempts=0;
    public boolean pending=false,retryable=false,successSignal=false;
    public long sentAt=0;
    public FillSession(int index,boolean pending){this.index=index;this.pending=pending;}
    public boolean maySubmit(long now){return !pending&&(!retryable||now-sentAt>=1200);}
    public void submitted(long now){pending=true;retryable=false;successSignal=false;attempts++;sentAt=now;}
    public void failedExplicitly(){if(pending){pending=false;retryable=true;}}
    public void saved(){index++;attempts=0;pending=false;retryable=false;successSignal=false;sentAt=0;}
    public static boolean permanentError(String text){return text.matches("(?s).*(验证码|滑块|操作频繁|请求频繁|证件.*错误|证件.*有误|身份证.*错误|身份证.*有误|身份.*不匹配|姓名.*有误|姓名.*不匹配|校验失败|人数上限|数量上限|已达上限|请先登录|登录失效).*");}
    public static boolean transientError(String text){return !permanentError(text)&&text.matches("(?s).*(添加.*失败|保存.*失败|网络异常|网络错误|服务繁忙|稍后重试).*");}
    public static boolean maskedIdMatches(String displayed,String id){
        String s=displayed.replaceAll("\\s+","").replace("身份证","").replace("证件号","").replace(":","").replace("：","");
        if(s.equalsIgnoreCase(id))return true;
        if(!s.matches("[0-9]{3,}[*•●]+[0-9Xx]{3,}"))return false;
        String[] parts=s.split("[*•●]+");return parts.length==2&&id.startsWith(parts[0])&&id.toUpperCase().endsWith(parts[1].toUpperCase());
    }
}
