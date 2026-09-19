#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
QA 独立验证脚本（Edward / 严过关）—— "AI 生成内容不含特殊符号" 的核心独立验证

与开发者自测无关：本脚本自建团队、自造数据、自己定义符号黑名单与反向断言，
真实调用 AI 工作台 / 聊天室接口，抓取返回文本逐条断言。

用法： python test/qa_symbol_check.py [port]
"""
import json
import re
import socket
import sys
import time
import urllib.error
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8093
BASE = "http://127.0.0.1:%d" % PORT
PASS = "CHANGE_ME"
TEAM_NAME = "QA符号验证团队"
results = []      # (阶段, 名称, ok, 详情)
samples = []      # (标签, 文本片段)
full_texts = []   # (标签, 全文) —— 原始证据，落盘
findings = []     # 严重发现

# ---------- 1. 符号黑名单（团队要求） ----------
LITERAL_BAD = "#*_`|~>【】·●▍✅❌📊📌⚠⏰📰🧠📨✨▶★☆■◆→←•▪▫◦‣"
# 团队要求里带【】；但实现方明确把【】当"中文标点"保留，单独统计
AMBIGUOUS = "【】"
RANGES = [
    (0x1F000, 0x1FAFF),  # emoji / 图形
    (0x2600, 0x27BF),    # 杂项符号与装饰
    (0x2190, 0x21FF),    # 箭头
    (0x2300, 0x23FF),    # 技术符号
    (0x2B00, 0x2BFF),    # 杂项符号与箭头
    (0x2500, 0x259F),    # 制表符 / 块元素
    (0x200B, 0x200F),    # 零宽字符
    (0xFE00, 0xFE0F),    # 变体选择符
    (0xFEFF, 0xFEFF),    # BOM
]


def scan(text):
    """返回命中的禁用符号列表 [(字符, U+码点, 说明)]"""
    hits = []
    if text is None:
        return hits
    for ch in text:
        cp = ord(ch)
        if ch in LITERAL_BAD:
            hits.append((ch, "U+%04X" % cp, "字面黑名单"))
        elif any(lo <= cp <= hi for lo, hi in RANGES):
            hits.append((ch, "U+%04X" % cp, "区段"))
    return hits


def scan_ambiguous(text):
    return [ch for ch in (text or "") if ch in AMBIGUOUS]


# ---------- HTTP ----------
def raw(method, path, token=None, body=None):
    url = BASE + path
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}


def wait_port(timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = socket.socket()
        s.settimeout(1)
        try:
            s.connect(("127.0.0.1", PORT))
            return True
        except Exception:
            time.sleep(1)
        finally:
            s.close()
    return False


def record(phase, name, ok, detail=""):
    results.append((phase, name, bool(ok), detail))
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("   " + str(detail) if detail else ""))


def check_text(phase, label, text, min_len=1, need_digit=False):
    """对一段 AI 文本做 无符号 + 内容未清坏 双断言"""
    snippet = (text or "")[:110].replace("\n", "\\n")
    samples.append((label, snippet))
    full_texts.append((label, text or ""))
    if not text or len(text.strip()) < min_len:
        record(phase, label + " 非空(>=%d字)" % min_len, False, "实际长度=%d 内容=[%s]" % (len(text or ""), snippet))
        return
    record(phase, label + " 非空(>=%d字)" % min_len, True, "长度=%d" % len(text))
    hits = scan(text)
    amb = scan_ambiguous(text)
    if hits:
        uniq = sorted(set("%s(%s,%s)" % (c, cp, w) for c, cp, w in hits))
        record(phase, label + " 无禁用符号", False, "命中: " + " ".join(uniq))
        findings.append("%s 命中禁用符号: %s  片段=[%s]" % (label, " ".join(uniq), snippet))
    else:
        record(phase, label + " 无禁用符号", True, "扫描 %d 字" % len(text))
    if amb:
        findings.append("%s 含【或】(实现方保留的中文标点，团队口径可能视为符号): 片段=[%s]" % (label, snippet))
    # 内容未清坏：至少含中文
    record(phase, label + " 含中文", bool(re.search(r"[\u4e00-\u9fff]", text)), "")
    if need_digit:
        record(phase, label + " 数字未丢失", bool(re.search(r"\d", text)), "")


def main():
    if not wait_port():
        print("服务未启动（端口 %d）" % PORT)
        return 2

    print("\n########## 阶段 0：准备账号与团队 ##########")
    _, r1 = raw("POST", "/api/auth/login", body={"account": "20230001", "password": PASS})
    _, r2 = raw("POST", "/api/auth/login", body={"account": "20230002", "password": PASS})
    t_owner = (r1.get("data") or {}).get("token")
    t_member = (r2.get("data") or {}).get("token")
    record("准备", "张三/李四登录", bool(t_owner and t_member))
    if not (t_owner and t_member):
        return 2

    # 用唯一团队名，避免复用脏团队
    team_name = TEAM_NAME + str(int(time.time()) % 100000)
    _, cr = raw("POST", "/api/team/create", t_owner, {"teamName": team_name})
    record("准备", "创建验证团队", cr.get("code") == 200, cr.get("msg") or cr.get("data"))
    _, my = raw("GET", "/api/team/my", t_owner)
    target = next((t for t in (my.get("data") or []) if t.get("teamName") == team_name), None)
    if target is None:
        print("无法取得团队，终止")
        return 2
    tid = target["id"]
    raw("POST", "/api/team/join", t_member, {"inviteCode": target.get("inviteCode")})

    # 造数据：先跑一次项目计划并导入，让洞察/周报有真实数字
    _, plan0 = raw("POST", "/api/ai/plan", t_owner, {"teamId": tid, "topic": "种子项目", "weeks": 4})
    p0 = (plan0.get("data") or {}).get("artifactId")
    if p0:
        raw("POST", "/api/ai/plan/import", t_owner, {"teamId": tid, "artifactId": p0})
    _, tl = raw("GET", "/api/task/list?teamId=%d" % tid, t_owner)
    print("  种子任务数 = %d" % len(tl.get("data") or []))

    print("\n########## 阶段 1：AI 工作台各路径抓文本 ##########")

    # 1) 团队洞察
    st, ins = raw("GET", "/api/ai/insight?teamId=%d" % tid, t_owner)
    d = ins.get("data") or {}
    record("洞察", "接口返回 200", ins.get("code") == 200, ins.get("msg"))
    check_text("洞察", "insight.report", d.get("report"), min_len=80, need_digit=True)

    # 2) 项目计划
    st, plan = raw("POST", "/api/ai/plan", t_owner,
                   {"teamId": tid, "topic": "客户管理后台二期", "weeks": 6})
    pd = plan.get("data") or {}
    record("计划", "接口返回 200", plan.get("code") == 200, plan.get("msg"))
    check_text("计划", "plan.goal", pd.get("goal"), min_len=4)
    check_text("计划", "plan.advice", pd.get("advice"), min_len=4)
    for si, stage in enumerate(pd.get("stages") or []):
        check_text("计划", "plan.stages[%d].stageName" % si, stage.get("stageName"), min_len=2)
        check_text("计划", "plan.stages[%d].goal" % si, stage.get("goal"), min_len=2)
        for ti, task in enumerate(stage.get("tasks") or []):
            check_text("计划", "plan.stages[%d].tasks[%d].taskContent" % (si, ti),
                       task.get("taskContent"), min_len=2)

    # 3) PPT
    st, ppt = raw("POST", "/api/ai/ppt", t_owner,
                  {"teamId": tid, "topic": "季度项目汇报", "slideCount": 8, "scene": "report", "theme": "blue"})
    od = ppt.get("data") or {}
    record("PPT", "接口返回 200", ppt.get("code") == 200, ppt.get("msg"))
    check_text("PPT", "ppt.subtitle", od.get("subtitle"), min_len=2)
    for si, s in enumerate(od.get("slides") or []):
        check_text("PPT", "ppt.slides[%d].title" % si, s.get("title"), min_len=1)
        for bi, b in enumerate(s.get("bullets") or []):
            check_text("PPT", "ppt.slides[%d].bullets[%d]" % (si, bi), b, min_len=1)

    # 4) 会议纪要拆任务
    st, mt = raw("POST", "/api/ai/meeting", t_owner, {
        "teamId": tid,
        "notes": "本周完成登录接口联调（张三，周五前）；李四整理接口文档，下周三交付；"
                 "王五负责压测报告，9月30日完成"})
    md = mt.get("data") or {}
    record("纪要", "接口返回 200", mt.get("code") == 200, mt.get("msg"))
    record("纪要", "拆分出任务数>=2", len(md.get("tasks") or []) >= 2, len(md.get("tasks") or []))
    for ti, task in enumerate(md.get("tasks") or []):
        check_text("纪要", "meeting.tasks[%d].taskContent" % ti, task.get("taskContent"), min_len=2)

    # 5) AI 周报
    st, wk = raw("POST", "/api/ai/weekly", t_owner, {"teamId": tid})
    wd = wk.get("data") or {}
    record("周报", "接口返回 200", wk.get("code") == 200, wk.get("msg"))
    check_text("周报", "weekly.report", wd.get("report"), min_len=80, need_digit=True)

    # 6) 团队问答（两问）
    st, ak = raw("POST", "/api/ai/ask", t_owner, {"teamId": tid, "question": "现在有几条逾期任务？"})
    ad = ak.get("data") or {}
    record("问答", "接口返回 200", ak.get("code") == 200, ak.get("msg"))
    check_text("问答", "ask.answer(逾期)", ad.get("answer"), min_len=3)
    st, ak2 = raw("POST", "/api/ai/ask", t_owner, {"teamId": tid, "question": "现在谁手上的任务最多？"})
    ad2 = ak2.get("data") or {}
    check_text("问答", "ask.answer(谁最忙)", ad2.get("answer"), min_len=3)

    # 7) 周报推送聊天室 + 8) 聊天室 @CoBot
    print("\n########## 阶段 2：聊天室（周报推送 + @CoBot）##########")
    _, base = raw("GET", "/api/chat/list?teamId=%d&afterId=0" % tid, t_owner)
    base_ids = [m["id"] for m in (base.get("data") or [])]
    after_id = max(base_ids) if base_ids else 0

    # 7) 推送周报
    if wd.get("artifactId"):
        raw("POST", "/api/ai/weekly/push", t_owner, {"teamId": tid, "artifactId": wd["artifactId"]})

    # 8) @CoBot：查询 / 提取 / 确认 / 我的任务 / 提醒
    raw("POST", "/api/chat/send", t_owner, {"teamId": tid, "content": "查一下团队现在有哪些任务"})
    raw("POST", "/api/chat/send", t_owner, {"teamId": tid, "content": "@CoBot 李四 周五 完成QA符号验证脚本"})
    raw("POST", "/api/chat/send", t_owner, {"teamId": tid, "content": "确认"})
    raw("POST", "/api/chat/send", t_owner, {"teamId": tid, "content": "我的任务"})
    raw("POST", "/api/chat/send", t_owner, {"teamId": tid, "content": "提醒我 10分钟后 检查符号清洗结果"})

    time.sleep(1)
    _, lst = raw("GET", "/api/chat/list?teamId=%d&afterId=%d" % (tid, after_id), t_owner)
    msgs = lst.get("data") or []
    bots = [m for m in msgs if m.get("senderType") == 1]
    print("  本轮新增消息 %d 条，其中 bot 回复 %d 条" % (len(msgs), len(bots)))
    record("聊天室", "抓到 bot 回复>=3", len(bots) >= 3, "bot=%d" % len(bots))
    for i, m in enumerate(bots):
        what = (m.get("msgContent") or "")[:30].replace("\n", "\\n")
        check_text("聊天室", "chat.bot[%d](%s...)" % (i, what), m.get("msgContent"), min_len=1)

    print("\n########## 阶段 3：反向断言（内容没被清坏）##########")
    ins_report = d.get("report") or ""
    wk_report = wd.get("report") or ""
    record("反向", "洞察报告含中文标点（。，、：）", bool(re.search(r"[。，、：]", ins_report)))
    record("反向", "洞察报告含数字/百分比", bool(re.search(r"\d", ins_report)))
    record("反向", "周报保留统计数字", bool(re.search(r"\d", wk_report)))
    # 日期未被清坏：以周报产物主题（含统计周期）为准，避免受 LLM 是否复述日期影响
    wk_topic = ""
    if wd.get("artifactId"):
        _, dt = raw("GET", "/api/ai/detail/%s" % wd["artifactId"], t_owner)
        wk_topic = (dt.get("data") or {}).get("topic") or ""
    record("反向", "周报统计周期保留日期（yyyy-MM-dd）",
           bool(re.search(r"\d{4}-\d{2}-\d{2}", wk_topic)),
           "topic=[%s]" % wk_topic)
    record("反向", "周报正文含日期或统计周期描述",
           bool(re.search(r"\d{4}-\d{2}-\d{2}", wk_report)) or "统计周期" in wk_report,
           "report含日期=%s" % bool(re.search(r"\d{4}-\d{2}-\d{2}", wk_report)))
    # 成员姓名完整性（本地模板必含；LLM 尽力）
    joined = " ".join([ins_report, wk_report, (ad.get("answer") or ""), (ad2.get("answer") or "")])
    has_zw = "张三" in joined
    has_ls = "李四" in joined
    record("反向", "成员姓名「张三」未被删字", has_zw, "（LLM 模式可能不逐一具名）")
    print("        注：李四 出现在 AI 文本中 = %s" % has_ls)

    # ---------- 汇总 ----------
    passed = sum(1 for _, _, ok, _ in results if ok)
    total = len(results)
    print("\n\n========== 关键样本（真实片段） ==========")
    for label, snip in samples[:40]:
        print("  %-42s %s" % (label, snip))
    print("\n========== 发现（需要人工判断/修复） ==========")
    if not findings:
        print("  无")
    else:
        for f in findings:
            print("  - " + f)
    print("\n==== QA 独立验证结果：%d/%d 通过 ====" % (passed, total))
    for phase, name, ok, det in results:
        if not ok:
            print("  FAILED -> [%s] %s : %s" % (phase, name, det))

    # 原始证据落盘（含全文，便于复核）
    with open("qa_symbol_raw.txt", "w", encoding="utf-8") as fh:
        for label, txt in full_texts:
            fh.write("===== %s =====\n%s\n\n" % (label, txt))
    print("原始文本已写入 qa_symbol_raw.txt")

    # 清理：解散团队
    _, dis = raw("POST", "/api/team/dismiss", t_owner, {"teamId": tid})
    record("清理", "解散验证团队", dis.get("code") == 200, dis.get("data") or dis.get("msg"))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
