package com.cobot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @提及解析器单元测试
 */
class MentionParserTest {

    private final MentionParser parser = new MentionParser();

    @Test
    @DisplayName("解析普通 @提及")
    void parseSimpleMention() {
        List<String> names = List.of("张三", "李四", "王五");
        List<String> found = parser.parse("@张三 这个任务今天给我", names);
        assertEquals(List.of("张三"), found);
    }

    @Test
    @DisplayName("长名字优先：@张三丰 不应被 @张三 抢先命中")
    void longerNameWins() {
        List<String> names = List.of("张三", "张三丰");
        List<String> found = parser.parse("@张三丰 看一下", names);
        assertEquals(List.of("张三丰"), found);
    }

    @Test
    @DisplayName("多个 @ 提及按出现顺序去重")
    void multipleMentions() {
        List<String> names = List.of("张三", "李四");
        List<String> found = parser.parse("@张三 @李四 一起看下 @张三", names);
        assertEquals(2, found.size(), "重复 @ 同一个人只应记录一次");
        assertTrue(found.containsAll(List.of("张三", "李四")));
    }

    @Test
    @DisplayName("没有团队成员名单时不解析（避免 @ 到团队外的人）")
    void emptyMemberList() {
        assertTrue(parser.parse("@任何人 你好", List.of()).isEmpty());
        assertTrue(parser.parse(null, List.of("张三")).isEmpty());
    }

    @Test
    @DisplayName("join / split 往返一致")
    void joinAndSplit() {
        String joined = parser.join(List.of("张三", "李四"));
        assertEquals("张三,李四", joined);
        assertEquals(List.of("张三", "李四"), parser.split(joined));
        assertNull(parser.join(List.of()));
        assertTrue(parser.split(null).isEmpty());
    }
}
