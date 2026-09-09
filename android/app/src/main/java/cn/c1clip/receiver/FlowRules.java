package cn.c1clip.receiver;
import java.util.Set;
/** Text-only routing for the supplied identity-management screens. */
public final class FlowRules {
    public enum Platform { MAOYAN, PIAOXINGQIU }
    public enum Step { HOME, PROFILE, LIST, FORM, UNKNOWN }
    public static String norm(String s){return s==null?"":s.replaceAll("\\s+","").replace("＋","+");}
    private static boolean has(Set<String> values,String part){for(String v:values)if(v.contains(part))return true;return false;}
    public static Step detect(Platform platform,Set<String> text){
        if(platform==Platform.PIAOXINGQIU){
            if(has(text,"姓名")&&has(text,"证件")&&(has(text,"新增观演/赛人")||has(text,"保存")))return Step.FORM;
            if(has(text,"新增观演/赛人"))return Step.LIST;
            if(has(text,"观演/赛人"))return Step.PROFILE;
            if(has(text,"我的"))return Step.HOME;
        }else{
            if(has(text,"姓名")&&has(text,"证件")&&(has(text,"添加观演人信息")||has(text,"确定")))return Step.FORM;
            if(has(text,"添加/修改观演人信息"))return Step.LIST;
            if(has(text,"观演人信息"))return Step.PROFILE;
            if(has(text,"我的"))return Step.HOME;
        }
        return Step.UNKNOWN;
    }
    public static String platformName(Platform p){return p==Platform.PIAOXINGQIU?"票星球":"猫眼";}
    public static String profileLabel(Platform p){return p==Platform.PIAOXINGQIU?"观演/赛人":"观演人信息";}
    public static String addLabel(Platform p){return p==Platform.PIAOXINGQIU?"新增观演/赛人":"添加/修改观演人信息";}
    public static String confirmLabel(Platform p){return p==Platform.PIAOXINGQIU?"保存":"确定";}
}
