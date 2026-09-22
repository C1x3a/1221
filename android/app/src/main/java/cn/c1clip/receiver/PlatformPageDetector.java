package cn.c1clip.receiver;

import java.util.*;

/**
 * Platform-specific, evidence-first page detector.
 * It does not click anything and never infers success from a page transition alone.
 */
public final class PlatformPageDetector {
    public enum Page { HOME, PROFILE, PERSON_LIST, ADD_FORM, CONSENT_DIALOG, UNKNOWN }

    public static final class Snapshot {
        public final Set<String> values;
        public final int editableCount;
        public final boolean maskedIdentity;
        public final boolean clickableAdd;
        public final boolean clickableConfirm;
        public Snapshot(Set<String> values,int editableCount,boolean maskedIdentity,boolean clickableAdd,boolean clickableConfirm){
            this.values=values==null?Collections.emptySet():values;
            this.editableCount=editableCount;
            this.maskedIdentity=maskedIdentity;
            this.clickableAdd=clickableAdd;
            this.clickableConfirm=clickableConfirm;
        }
    }

    public static final class Result {
        public final Page page;
        public final String evidence;
        Result(Page page,String evidence){this.page=page;this.evidence=evidence;}
    }

    private static boolean has(Set<String> v,String part){for(String s:v)if(s!=null&&s.contains(part))return true;return false;}
    private static boolean any(Set<String> v,String... parts){for(String p:parts)if(has(v,p))return true;return false;}\n    private static boolean exact(Set<String> v,String value){for(String s:v)if(s!=null&&s.equals(value))return true;return false;}

    public static Result detect(FlowRules.Platform platform,Snapshot s){
        return platform==FlowRules.Platform.PIAOXINGQIU?detectPlanet(s):detectMaoyan(s);
    }

    public static boolean identifiesFlow(FlowRules.Platform platform,Snapshot s){
        Page p=detect(platform,s).page;
        if(p==Page.PERSON_LIST||p==Page.ADD_FORM||p==Page.CONSENT_DIALOG)return true;
        if(platform==FlowRules.Platform.MAOYAN)
            return any(s.values,"常用信息","观演人信息")&&(s.maskedIdentity||any(s.values,"添加观演","修改观演","身份证"));
        return any(s.values,"观演/赛人","观演赛人","观演人")&&(s.maskedIdentity||any(s.values,"证件号码","身份证","新增观演"));
    }

    private static Result detectMaoyan(Snapshot s){
        Set<String> v=s.values;
        boolean name=has(v,"姓名");
        boolean id=any(v,"证件号","证件号码","身份证");
        boolean confirm=s.clickableConfirm||any(v,"确定","保存");
        boolean agreement=any(v,"我已阅读并同意","实名说明");
        boolean formTitle=any(v,"添加观演人信息","新增观演人信息");
        boolean listContext=any(v,"常用信息","常用观演人");
        boolean personTitle=has(v,"观演人信息");
        boolean addText=any(v,"添加/修改观演人信息","+添加/修改观演人信息","修改观演人信息","添加观演人");

        if((s.editableCount>=2&&(name||id||confirm||formTitle))||(name&&id&&(confirm||agreement||formTitle)))
            return new Result(Page.ADD_FORM,"猫眼表单：输入框/姓名/证件/确认");
        if(agreement&&confirm)return new Result(Page.ADD_FORM,"猫眼表单：实名协议+确认");
        if((s.clickableAdd&&(listContext||personTitle||s.maskedIdentity))||(listContext&&addText&&!name&&!id)||(listContext&&personTitle&&s.maskedIdentity))
            return new Result(Page.PERSON_LIST,"猫眼人员列表：常用信息/人员卡片/新增按钮");
        if(personTitle&&!listContext&&!name&&!id&&any(v,"我的","我的订单","常用信息管理"))
            return new Result(Page.PROFILE,"猫眼个人页：观演人信息入口");
        if(has(v,"我的")&&any(v,"首页","电影","影院","演出","我的订单"))
            return new Result(Page.HOME,"猫眼首页/我的导航");
        return new Result(Page.UNKNOWN,"猫眼证据不足");
    }

    private static Result detectPlanet(Snapshot s){
        Set<String> v=s.values;
        boolean name=has(v,"姓名");
        boolean id=any(v,"证件号码","证件号","身份证");
        boolean confirm=s.clickableConfirm||any(v,"保存","确认");
        boolean agreement=any(v,"请阅读并同意","敏感个人信息授权书");
        boolean formTitle=any(v,"新增观演/赛人","新增观演人","添加观演/赛人","添加观演人");
        boolean personTitle=any(v,"观演/赛人","观演赛人","观演人","观演人管理");\n        boolean exactPersonTitle=exact(v,"观演/赛人")||exact(v,"观演赛人")||exact(v,"观演人")||exact(v,"观演人管理");
        boolean addText=any(v,"新增观演/赛人","新增观演人","添加观演/赛人","添加观演人");

        if(has(v,"敏感个人信息授权书")&&has(v,"同意")&&has(v,"不同意"))
            return new Result(Page.CONSENT_DIALOG,"票星球敏感信息授权弹窗");
        if((s.editableCount>=2&&(name||id||confirm||formTitle))||(name&&id&&(confirm||agreement||formTitle)))
            return new Result(Page.ADD_FORM,"票星球表单：输入框/姓名/证件/保存");
        if(agreement&&confirm)return new Result(Page.ADD_FORM,"票星球表单：协议+保存");
        if((s.clickableAdd&&personTitle)||(exactPersonTitle&&addText&&!name&&!id)||(personTitle&&s.maskedIdentity))
            return new Result(Page.PERSON_LIST,"票星球人员列表：观演人标题/人员卡片/新增按钮");
        if(personTitle&&!name&&!id&&any(v,"我的抢票","全部订单","身份认证","实名信息"))
            return new Result(Page.PROFILE,"票星球个人页：观演人入口");
        if(has(v,"我的")&&any(v,"首页","抢票","订单","票夹"))
            return new Result(Page.HOME,"票星球首页/我的导航");
        return new Result(Page.UNKNOWN,"票星球证据不足");
    }

    private PlatformPageDetector(){}
}
