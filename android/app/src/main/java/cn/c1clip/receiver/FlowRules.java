package cn.c1clip.receiver;
import java.util.Set;
/** Multi-evidence routing for Maoyan / Piaoxingqiu identity-management screens. */
public final class FlowRules {
    public enum Platform { MAOYAN, PIAOXINGQIU }
    public enum Step { HOME, PROFILE, LIST, FORM, UNKNOWN }
    public enum FormStage { NAME, ID, AGREEMENT, CONFIRM, SAVING }
    public static String norm(String s){return s==null?"":s.replaceAll("\\s+","").replace("＋","+");}
    private static boolean has(Set<String> values,String part){for(String v:values)if(v.contains(part))return true;return false;}
    private static boolean any(Set<String> values,String... parts){for(String p:parts)if(has(values,p))return true;return false;}
    private static boolean maskedIdentity(Set<String> values){for(String v:values)if(v.matches(".*[0-9]{3,}[*•●]+[0-9Xx]{3,}.*"))return true;return false;}

    /**
     * Score several independent signals instead of relying on one exact title.
     * Synthetic markers are added by FillService:
     *   __forminputs__   >=2 editable fields
     *   __maskedidentity__ masked ID row
     *   __addcontrol__   add/modify control located by text/structure/device profile
     */
    public static Step detect(Platform platform,Set<String> text){
        boolean formInputs=has(text,"__forminputs__");
        boolean masked=maskedIdentity(text)||has(text,"__maskedidentity__");
        boolean addControl=has(text,"__addcontrol__");
        int form=0,list=0,profile=0,home=0;

        if(formInputs)form+=5;
        if(addControl)list+=8;
        if(masked)list+=3;

        if(platform==Platform.PIAOXINGQIU){
            if(any(text,"保存","确认","完成"))form+=2;
            if(has(text,"姓名")&&any(text,"证件","身份证")){form+=4;if(any(text,"新增观演/赛人","新增观演人","添加观演/赛人","添加观演人","新增观演人信息"))form+=3;}
            if(any(text,"请阅读并同意","敏感个人信息授权书"))form+=3;
            if(any(text,"新增观演/赛人","新增观演人","添加观演/赛人","添加观演人","新增观演人信息","观演人管理"))list+=6;
            if(masked&&any(text,"观演/赛人","观演人","赛人","常用观演人"))list+=4;
            if(any(text,"观演/赛人","观演赛人","观演人")&&any(text,"我的抢票","全部订单","身份认证","实名信息"))profile+=5;
            if(has(text,"我的"))home+=2;
        }else{
            if(any(text,"确定","保存","完成"))form+=2;
            if(has(text,"姓名")&&any(text,"证件","身份证")){form+=4;if(any(text,"添加/修改观演人信息","修改观演人信息","新增观演人信息","添加观演人信息","添加观演人"))form+=3;}
            if(any(text,"我已阅读并同意","实名说明"))form+=3;
            if(any(text,"添加/修改观演人信息","修改观演人信息","新增观演人信息","添加观演人信息","添加观演人","修改观演人","常用观演人"))list+=6;
            if(has(text,"常用信息")&&any(text,"观演人信息","修改观演人信息","添加观演人信息","身份证"))list+=5;
            if(masked&&any(text,"观演人信息","常用信息","观演人"))list+=4;
            if(any(text,"观演人信息","常用信息")&&any(text,"我的","实名","常用"))profile+=4;
            if(has(text,"我的"))home+=2;
        }

        // Completed form evidence wins over a title that happens to contain "添加观演人".
        if(form>=8)return Step.FORM;
        if(list>=7&&form<8)return Step.LIST;
        if(form>=6)return Step.FORM;
        if(list>=5)return Step.LIST;
        if(profile>=4)return Step.PROFILE;
        if(home>=2)return Step.HOME;
        return Step.UNKNOWN;
    }

    /** Strong enough to bind a different foreground WebView package to the running task. */
    public static boolean identifiesPlatformFlow(Platform platform,Set<String> text){
        if(platform==Platform.PIAOXINGQIU){
            if(any(text,"新增观演/赛人","新增观演人","添加观演/赛人","添加观演人","观演人管理","请阅读并同意《敏感个人信息授权书》"))return true;
            return any(text,"观演/赛人","观演赛人","观演人")&&(any(text,"新增观演","证件号码","身份证","实名")||maskedIdentity(text));
        }
        if(any(text,"添加/修改观演人信息","修改观演人信息","新增观演人信息","添加观演人信息","添加观演人","我已阅读并同意《实名说明》"))return true;
        return any(text,"常用信息","观演人信息","常用观演人")&&(any(text,"添加观演","修改观演","身份证","实名")||maskedIdentity(text));
    }
    public static String platformName(Platform p){return p==Platform.PIAOXINGQIU?"票星球":"猫眼";}
    public static String profileLabel(Platform p){return p==Platform.PIAOXINGQIU?"观演/赛人":"观演人信息";}
    public static String addLabel(Platform p){return p==Platform.PIAOXINGQIU?"新增观演/赛人":"添加/修改观演人信息";}
    public static String confirmLabel(Platform p){return p==Platform.PIAOXINGQIU?"保存":"确定";}
    public static boolean planetConsentDialog(Set<String> text){return has(text,"已阅读并同意")&&has(text,"敏感个人信息授权书")&&has(text,"同意")&&has(text,"不同意");}
    public static FormStage formStage(boolean nameReady,boolean idReady,boolean agreed,boolean submitted){if(submitted)return FormStage.SAVING;if(!nameReady)return FormStage.NAME;if(!idReady)return FormStage.ID;if(!agreed)return FormStage.AGREEMENT;return FormStage.CONFIRM;}
    public static String stageName(FormStage s){return switch(s){case NAME->"填写姓名";case ID->"填写身份证";case AGREEMENT->"勾选实名协议";case CONFIRM->"确认保存";case SAVING->"等待保存结果";};}
}
