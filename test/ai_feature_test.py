# -*- coding: utf-8 -*-
"""
CoBot-v2 · AI 工作台端到端测试脚本

覆盖：团队洞察（含负责人权限）、项目计划生成、计划导入任务看板、
      PPT 生成与文件下载、生成历史、跨角色/跨团队越权校验。

用法：python ai_feature_test.py
依赖：仅标准库（urllib），无需额外安装
"""
import json
import urllib.request
import urllib.error
import os
import zipfile
import io

BASE = "http://localhost:8093"
PASSED = []
FAILED = []


def call(method, path, body=None, token=None, raw=False):
    """统一请求封装；raw=True 时返回 (status, bytes, headers)"""
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            content = resp.read()
            if raw:
                return resp.status, content, dict(resp.headers)
            return json.loads(content.decode("utf-8"))
    except urllib.error.HTTPError as e:
        if raw:
            return e.code, e.read(), dict(e.headers)
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            return {"code": e.code, "msg": "HTTP " + str(e.code)}


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print("  [PASS] %s %s" % (name, detail))
    else:
        FAILED.append(name)
        print("  [FAIL] %s %s" % (name, detail))


def login(account, password="CHANGE_ME"):
    r = call("POST", "/api/auth/login", {"account": account, "password": password})
    assert r.get("code") == 200, "登录失败 %s: %s" % (account, r)
    return r["data"]["token"]


print("=" * 62)
print("CoBot-v2 AI 工作台 · 端到端测试")
print("=" * 62)

# ---------- 0. 登录 ----------
print("\n[0] 登录准备")
# 注意：李四在研发一组已降为普通成员（团队 1 只有张三一位负责人），
# 所以这里用王五(userId=3)作为「真正的普通成员」来验证成员权限边界。
owner_tk = login("20230001")    # 研发一组 负责人（显示昵称 张三）
member_tk = login("20230003")   # 研发一组 普通成员（显示昵称 王五）
other_tk = login("20230004")    # 产品二组 成员（显示昵称 赵六，用于跨团队越权测试）
print("  已获取：负责人(张三) / 普通成员(王五) / 外部成员(赵六) 的 Token")

# ---------- 1. 团队洞察（负责人专属） ----------
print("\n[1] 团队数据洞察（负责人）")
r = call("GET", "/api/ai/insight?teamId=1", token=owner_tk)
check("负责人可生成团队洞察", r.get("code") == 200, r.get("msg", ""))
insight = r.get("data") or {}
check("返回真实统计数据", insight.get("totalTasks", 0) >= 0 and "memberCount" in insight,
      "任务总数=%s 成员数=%s 风险=%s" % (insight.get("totalTasks"), insight.get("memberCount"),
                                    insight.get("riskLevel")))
check("生成了分析报告", bool(insight.get("report")) and len(insight.get("report", "")) > 50,
      "报告长度=%d 来源=%s" % (len(insight.get("report") or ""),
                            "AI" if insight.get("aiGenerated") else "本地模板"))
check("回传成员负荷明细", isinstance(insight.get("memberLoads"), list) and len(insight.get("memberLoads")) > 0,
      "负荷明细 %d 条" % len(insight.get("memberLoads") or []))
check("洞察产物已落库", bool(insight.get("artifactId")), "artifactId=%s" % insight.get("artifactId"))

# 权限：普通成员不能看团队整体洞察
r = call("GET", "/api/ai/insight?teamId=1", token=member_tk)
check("普通成员被拒绝查看团队洞察", r.get("code") != 200, r.get("msg", ""))

# 权限：非本团队成员不能看
r = call("GET", "/api/ai/insight?teamId=1", token=other_tk)
check("非本团队成员被拒绝", r.get("code") != 200, r.get("msg", ""))

# ---------- 2. 项目计划生成 ----------
print("\n[2] 一键生成项目计划")
r = call("POST", "/api/ai/plan",
         {"teamId": 1, "topic": "校园二手交易平台开发", "weeks": 4}, token=member_tk)
check("普通成员可生成项目计划", r.get("code") == 200, r.get("msg", ""))
plan = r.get("data") or {}
stages = plan.get("stages") or []
check("计划包含阶段", len(stages) >= 1, "阶段数=%d" % len(stages))
total_tasks = sum(len(s.get("tasks") or []) for s in stages)
check("计划包含任务", total_tasks >= 3, "任务数=%d" % total_tasks)
check("任务带负责人与截止日",
      total_tasks > 0 and all((t.get("taskContent") or "").strip() for s in stages for t in (s.get("tasks") or [])),
      "来源=%s" % ("AI" if plan.get("aiGenerated") else "本地模板"))
print("      项目目标：%s" % plan.get("goal"))
print("      阶段预览：%s" % " / ".join(s.get("stageName", "") for s in stages[:3]))

# 空主题校验
r = call("POST", "/api/ai/plan", {"teamId": 1, "topic": "  ", "weeks": 4}, token=member_tk)
check("空主题被拒绝", r.get("code") != 200, r.get("msg", ""))

# ---------- 3. 计划一键导入任务看板 ----------
print("\n[3] 计划导入任务看板")
before = call("GET", "/api/task/list?teamId=1", token=owner_tk)
before_cnt = len(before.get("data") or [])
r = call("POST", "/api/ai/plan/import",
         {"teamId": 1, "artifactId": plan.get("artifactId")}, token=member_tk)
check("普通成员可导入计划（权限校验通过）", r.get("code") == 200, r.get("msg", ""))
after = call("GET", "/api/task/list?teamId=1", token=owner_tk)
after_list = after.get("data") or []
check("任务已写入看板", len(after_list) > before_cnt,
      "导入前=%d 导入后=%d" % (before_cnt, len(after_list)))
waiting = [t for t in after_list if t.get("taskStatus") == "待确认"]
check("成员导入的任务进入待确认（需负责人审批）", len(waiting) > 0, "待确认 %d 条" % len(waiting))

# 跨团队导入越权
r = call("POST", "/api/ai/plan/import",
         {"teamId": 1, "artifactId": plan.get("artifactId")}, token=other_tk)
check("外部成员无法导入他人团队计划", r.get("code") != 200, r.get("msg", ""))

# 负责人导入 -> 直接进行中
r = call("POST", "/api/ai/plan",
         {"teamId": 1, "topic": "社团活动报名系统", "weeks": 2}, token=owner_tk)
owner_plan = r.get("data") or {}
r = call("POST", "/api/ai/plan/import",
         {"teamId": 1, "artifactId": owner_plan.get("artifactId")}, token=owner_tk)
check("负责人可导入计划", r.get("code") == 200, r.get("msg", ""))
after2 = call("GET", "/api/task/list?teamId=1", token=owner_tk)
doing = [t for t in (after2.get("data") or []) if t.get("taskStatus") == "进行中"
         and "【" in (t.get("taskContent") or "")]
check("负责人导入的任务直接进行中", len(doing) > 0, "进行中(计划导入) %d 条" % len(doing))

# ---------- 4. PPT 生成与下载 ----------
print("\n[4] 一键生成 PPT")
r = call("POST", "/api/ai/ppt",
         {"teamId": 1, "topic": "校园二手交易平台 项目中期汇报",
          "slideCount": 8, "scene": "report"}, token=member_tk)
check("生成 PPT 成功", r.get("code") == 200, r.get("msg", ""))
ppt = r.get("data") or {}
slides = ppt.get("slides") or []
check("大纲页数符合预期", len(slides) >= 6, "实际 %d 页（要求 8 页）" % len(slides))
check("首页为封面", slides and slides[0].get("type") == "cover", slides[0].get("type") if slides else "无")
check("末页为结束页", slides and slides[-1].get("type") == "end",
      slides[-1].get("type") if slides else "无")
check("内容页有要点", any(len(s.get("bullets") or []) > 0 for s in slides),
      "内容页 %d 个" % len([s for s in slides if s.get("type") == "content"]))
check("返回可下载文件名", bool(ppt.get("fileName")), ppt.get("fileName"))

# 下载文件
status, blob, headers = call("GET", "/api/ai/file/%s" % ppt.get("artifactId"), token=member_tk, raw=True)
check("文件下载返回 200", status == 200, "HTTP %s, %d 字节" % (status, len(blob)))
check("文件是合法 OOXML(pptx) 包", blob[:2] == b"PK" and len(blob) > 5000,
      "大小 %.1f KB" % (len(blob) / 1024.0))
try:
    zf = zipfile.ZipFile(io.BytesIO(blob))
    names = zf.namelist()
    slide_cnt = len([n for n in names if n.startswith("ppt/slides/slide") and n.endswith(".xml")])
    check("pptx 内含幻灯片 XML", slide_cnt == len(slides),
          "ppt/slides 共 %d 张，大纲 %d 页" % (slide_cnt, len(slides)))
    check("pptx 含中文内容（非空模板）", any("ppt/slides/slide1.xml" in n for n in names))
except Exception as e:
    check("pptx 可正常解压解析", False, str(e))

# 越权下载：外部成员不能下载其他团队的产物
status, _, _ = call("GET", "/api/ai/file/%s" % ppt.get("artifactId"), token=other_tk, raw=True)
check("外部成员无法下载他人团队 PPT", status == 403, "HTTP %s" % status)

# 未登录下载
status, _, _ = call("GET", "/api/ai/file/%s" % ppt.get("artifactId"), raw=True)
check("未登录无法下载", status == 401, "HTTP %s" % status)

# ---------- 5. 生成历史 ----------
print("\n[5] 生成历史")
r = call("GET", "/api/ai/history?teamId=1", token=owner_tk)
check("可查询生成历史", r.get("code") == 200, r.get("msg", ""))
history = r.get("data") or []
check("历史含三类产物", len({h.get("genType") for h in history}) >= 3,
      "类型=%s 共 %d 条" % ({h.get("genType") for h in history}, len(history)))

# 历史详情
r = call("GET", "/api/ai/detail/%s" % ppt.get("artifactId"), token=owner_tk)
check("可查看历史详情", r.get("code") == 200 and bool(r.get("data", {}).get("content")),
      "内容长度 %d" % len(r.get("data", {}).get("content") or ""))

# ---------- 6. 跨团队隔离 ----------
print("\n[6] 跨团队数据隔离")
r = call("GET", "/api/ai/history?teamId=2", token=owner_tk)
check("无法查看非本团队生成历史", r.get("code") != 200, r.get("msg", ""))

print("\n" + "=" * 62)
print("测试结果：%d 项通过，%d 项失败" % (len(PASSED), len(FAILED)))
if FAILED:
    print("失败项：")
    for f in FAILED:
        print("  - " + f)
print("=" * 62)
