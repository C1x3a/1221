package cn.c1clip.receiver;
import java.util.Set;
/** Only the four supplied Maoyan pages. No purchase, delete, agreement or submit action. */
public final class FlowRules {
    public enum Step { HOME, PROFILE, LIST, FORM, UNKNOWN }
    public static String norm(String s){return s==null?"":s.replaceAll("\\s+","").replace("＋","+");}
    public static Step detect(Set<String> text){
        if(text.contains("添加观演人信息")&&text.contains("姓名")&&text.contains("证件号")&&text.contains("身份证"))return Step.FORM;
        if(text.contains("观演人信息")&&(text.contains("+添加/修改观演人信息")||text.contains("添加/修改观演人信息")))return Step.LIST;
        if(text.contains("我的订单")&&text.contains("观演人信息"))return Step.PROFILE;
        if(text.contains("我的")&&text.contains("首页")&&(text.contains("电影/影院")||text.contains("演唱会")))return Step.HOME;
        return Step.UNKNOWN;
    }
}
