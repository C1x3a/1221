package cn.c1clip.receiver;
import java.util.Set;
/** Text-only routing for the supplied identity-management screens. */
public final class FlowRules {
    public enum Platform { MAOYAN, PIAOXINGQIU }
    public enum Step { HOME, PROFILE, LIST, FORM, UNKNOWN }
    public enum FormStage { NAME, ID, AGREEMENT, CONFIRM, SAVING }
    public static String norm(String s){return s==null?"":s.replaceAll("\\s+","").replace("＋","+");}
    private static boolean has(Set<String> values,String part){for(String v:values)if(v.contains(part))return true;return false;}
    private static boolean any(Set<String> values,String... parts){for(String p:parts)if(has(values,p))return true;return false;}
    private static boolean maskedIdentity(Set<String> values){for(String v:values)if(v.matches(".*[0-9]{3,}[*•●]+[0-9Xx]{3,}.*"))return true;return false;}
    public static Step detect(Platform platform,Set<String> text){
        if(platform==Platform.PIAOXINGQIU){
            if((has(text,"__forminputs__")&&any(text,"保存","确认"))||(has(text,"姓名")&&any(text,"证件","身份证")&&any(text,"新增观演/赛人","新增观演人","证件类型","保存"))||has(text,"请阅读并同意")&&any(text,"保存","确认"))return Step.FORM;
            // Some Android/WebView combinations expose person rows but hide the toolbar and page title.
            // A masked identity cannot be a blank add form, so it is a safe list-level signal after FORM.
            if(maskedIdentity(text)||any(text,"新增观演/赛人","新增观演人","添加观演/赛人"))return Step.LIST;
            if(any(text,"观演/赛人","观演赛人")&&any(text,"我的抢票","全部订单","身份认证"))return Step.PROFILE;
            if(has(text,"我的"))return Step.HOME;
        }else{
            if((has(text,"__forminputs__")&&any(text,"确定","保存"))||(has(text,"姓名")&&any(text,"证件","身份证")&&any(text,"添加观演人信息","新增观演人信息","证件类型","确定","保存"))||has(text,"我已阅读并同意")&&any(text,"确定","保存"))return Step.FORM;
            // 猫眼有旧人员时会把按钮文字拆成多个无障碍节点；“常用信息”是该列表页稳定标题。
            if(has(text,"常用信息")&&any(text,"观演人信息","修改观演人信息","添加观演人信息","身份证"))return Step.LIST;
            if(maskedIdentity(text)||any(text,"添加/修改观演人信息","修改观演人信息","新增观演人信息"))return Step.LIST;
            if(has(text,"观演人信息"))return Step.PROFILE;
            if(has(text,"我的"))return Step.HOME;
        }
        return Step.UNKNOWN;
    }
    public static String platformName(Platform p){return p==Platform.PIAOXINGQIU?"票星球":"猫眼";}
    public static String profileLabel(Platform p){return p==Platform.PIAOXINGQIU?"观演/赛人":"观演人信息";}
    public static String addLabel(Platform p){return p==Platform.PIAOXINGQIU?"新增观演/赛人":"添加/修改观演人信息";}
    public static String confirmLabel(Platform p){return p==Platform.PIAOXINGQIU?"保存":"确定";}
    public static boolean planetConsentDialog(Set<String> text){return has(text,"已阅读并同意")&&has(text,"敏感个人信息授权书")&&has(text,"同意")&&has(text,"不同意");}
    public static FormStage formStage(boolean nameReady,boolean idReady,boolean agreed,boolean submitted){if(submitted)return FormStage.SAVING;if(!nameReady)return FormStage.NAME;if(!idReady)return FormStage.ID;if(!agreed)return FormStage.AGREEMENT;return FormStage.CONFIRM;}
    public static String stageName(FormStage s){return switch(s){case NAME->"填写姓名";case ID->"填写身份证";case AGREEMENT->"勾选实名协议";case CONFIRM->"确认保存";case SAVING->"等待保存结果";};}
}
