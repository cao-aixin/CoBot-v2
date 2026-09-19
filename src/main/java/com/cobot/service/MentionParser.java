package com.cobot.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @提及解析器
 *
 * <p>从消息文本中找出被 @ 的团队成员用户名。
 *
 * <p>匹配策略：<b>按用户名长度倒序</b>匹配，避免"张三丰"被"张三"抢先命中；
 * 同时基于"消息里出现 @名字"这一朴素规则，而不是正则猜名字边界 ——
 * 中文没有空格分词，@ 后面的名字边界本来就靠成员名单来确定，这样最稳。
 */
@Component
public class MentionParser {

    /**
     * 解析 @提及
     *
     * <p>实现要点：先定位每个 {@code @} 的位置，再从这里做<b>最长前缀匹配</b>。
     * 不能简单用 {@code text.contains("@" + name)} —— 那样 "@张三丰" 会因为以 "@张三" 开头，
     * 被"张三"抢先命中，导致同时 @ 到两个人（这是单测抓出来的真实缺陷）。
     *
     * @param text        消息文本
     * @param memberNames 当前团队成员用户名列表（限定在团队内，防止 @ 到团队外的人）
     * @return 被提及的用户名（去重，保持出现的先后顺序）
     */
    public List<String> parse(String text, List<String> memberNames) {
        List<String> found = new ArrayList<>();
        if (text == null || text.isBlank() || memberNames == null || memberNames.isEmpty()) {
            return found;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '@') {
                continue;
            }
            // 在该 @ 之后做最长前缀匹配：名单里更长的名字优先
            String best = null;
            for (String name : memberNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (text.startsWith(name, i + 1) && (best == null || name.length() > best.length())) {
                    best = name;
                }
            }
            if (best != null && !found.contains(best)) {
                found.add(best);
            }
        }
        return found;
    }

    /**
     * 把提及列表拼成库中存储的字符串（逗号分隔）
     */
    public String join(List<String> mentions) {
        return (mentions == null || mentions.isEmpty()) ? null : String.join(",", mentions);
    }

    /**
     * 反序列化库中的提及字符串
     */
    public List<String> split(String mentions) {
        if (mentions == null || mentions.isBlank()) {
            return List.of();
        }
        return List.of(mentions.split(","));
    }
}
