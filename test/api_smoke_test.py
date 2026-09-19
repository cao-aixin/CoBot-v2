#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CoBot-v2 接口冒烟测试（真实起服务 + 真实 MySQL，端到端走 HTTP）

用途
----
改完后端接口或前端调用后，快速回归一遍主链路，避免"改 A 坏 B"。

前置
----
1. MySQL 已启动，且已执行 sql/cobot_db.sql 与 sql/upgrade-hardening.sql
2. 应用已启动并监听 8093（可在 IDEA 里跑，也可：java -jar target/cobot-web-agent-2.0.0.jar --server.port=8093）
3. 依赖演示账号：20230001(张三) / 20230002(李四)，密码 123456

覆盖范围
--------
登录、团队创建/加入/成员、@提及、未读计数与已读、附件上传/下载、消息撤回、
任务看板列表与 Excel 导出、AI 会议纪要拆任务+导入、AI 周报+推送、AI 数据问答、
邀请码策略（有效期/次数）、越权防护（成员不能重置邀请码）。

清理
----
脚本会新建一个「冒烟测试团队」，结束时自动解散，不留脏数据；
若发现同名团队已存在则复用，且不做解散（避免误删你自己建的数据）。

用法
----
    python test/api_smoke_test.py            # 默认 http://127.0.0.1:8093
    python test/api_smoke_test.py 8093       # 指定端口
"""
import json
import socket
import sys
import time
import urllib.error
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8093
BASE = "http://127.0.0.1:%d" % PORT
PASS = "123456"
TEAM_NAME = "冒烟测试团队"
results = []


def wait_port(timeout=90):
    """等服务端口就绪，避免脚本比应用先跑起来"""
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


def raw(method, path, token=None, body=None, multipart=None):
    """底层请求：支持 JSON 与 multipart 两种 body"""
    url = BASE + path
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if multipart is not None:
        boundary = "----cobotsmoke1234567890"
        parts = []
        for k, v in multipart.get("fields", {}).items():
            parts.append(("--" + boundary + "\r\n"
                          "Content-Disposition: form-data; name=\"" + k + "\"\r\n\r\n"
                          + str(v) + "\r\n").encode("utf-8"))
        fn, content, ctype = multipart["file"]
        parts.append(("--" + boundary + "\r\n"
                      "Content-Disposition: form-data; name=\"file\"; filename=\"" + fn + "\"\r\n"
                      "Content-Type: " + ctype + "\r\n\r\n").encode("utf-8"))
        parts.append(content)
        parts.append(("\r\n--" + boundary + "--\r\n").encode("utf-8"))
        data = b"".join(parts)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def call(method, path, token=None, body=None):
    st, data = raw(method, path, token, body)
    try:
        return st, json.loads(data.decode("utf-8"))
    except Exception:
        return st, data


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("  " + str(detail) if detail else ""))


def upload(token, tid, filename, content):
    st, data = raw("POST", "/api/chat/upload", token, None,
                   {"fields": {"teamId": tid}, "file": (filename, content, "text/plain")})
    try:
        return st, json.loads(data.decode("utf-8"))
    except Exception:
        return st, {}


def main():
    if not wait_port():
        print("服务未在超时内启动，请先运行应用（端口 %d）" % PORT)
        return 2

    print("\n=== A. 登录 ===")
    _, r1 = call("POST", "/api/auth/login", body={"account": "20230001", "password": PASS})
    _, r2 = call("POST", "/api/auth/login", body={"account": "20230002", "password": PASS})
    check("张三登录", r1.get("code") == 200, r1.get("msg"))
    check("李四登录", r2.get("code") == 200, r2.get("msg"))
    t1 = (r1.get("data") or {}).get("token")
    t2 = (r2.get("data") or {}).get("token")
    if not t1 or not t2:
        print("登录失败，终止")
        return 2

    print("\n=== B. 团队 ===")
    _, my = call("GET", "/api/team/my", t1)
    target = next((t for t in (my.get("data") or []) if t.get("teamName") == TEAM_NAME), None)
    created = False
    if target is None:
        _, cr = call("POST", "/api/team/create", t1, {"teamName": TEAM_NAME})
        check("创建测试团队", cr.get("code") == 200, cr.get("msg"))
        created = True
        _, my = call("GET", "/api/team/my", t1)
        target = next((t for t in (my.get("data") or []) if t.get("teamName") == TEAM_NAME), None)
    check("取得测试团队", target is not None)
    if target is None:
        return 2
    tid = target["id"]
    invite = target.get("inviteCode")

    _, join = call("POST", "/api/team/join", t2, {"inviteCode": invite})
    idem = "已" in str(join.get("msg"))   # 已在团内属于正常幂等，不算失败
    check("李四加入团队", join.get("code") == 200 or idem, join.get("data") or join.get("msg"))

    _, mem = call("GET", "/api/team/members?teamId=%d" % tid, t1)
    names = [m.get("username") for m in (mem.get("data") or [])]
    check("成员列表含张三/李四", "张三" in names and "李四" in names, names)

    print("\n=== C. @提及 与 未读 ===")
    _, base = call("GET", "/api/chat/list?teamId=%d&afterId=0" % tid, t1)
    ids = [m["id"] for m in (base.get("data") or [])]
    after_id = max(ids) if ids else 0

    _, send = call("POST", "/api/chat/send", t1,
                   {"teamId": tid, "content": "@李四 明天下午3点前把接口文档发我"})
    check("发送 @提及 消息", send.get("code") == 200, send.get("msg"))

    _, lst = call("GET", "/api/chat/list?teamId=%d&afterId=%d" % (tid, after_id), t2)
    msgs = lst.get("data") or []
    check("被@的人看到 mentionMe=true", any(m.get("mentionMe") for m in msgs),
          [(m.get("msgContent"), m.get("mentions")) for m in msgs][:2])

    _, un = call("GET", "/api/chat/unread?teamId=%d" % tid, t2)
    _, all_ = call("GET", "/api/chat/list?teamId=%d&afterId=0" % tid, t2)
    unread_n = un.get("data") or 0
    check("未读数 > 0", unread_n > 0, unread_n)
    check("未读数不超过本团队消息总数（防止用全局ID差值虚高）",
          unread_n <= len(all_.get("data") or []), (unread_n, len(all_.get("data") or [])))

    last_id = msgs[-1]["id"] if msgs else 0
    _, rd = call("POST", "/api/chat/read", t2, {"teamId": tid, "lastReadId": last_id})
    check("标记已读", rd.get("code") == 200, rd.get("msg"))
    _, un2 = call("GET", "/api/chat/unread?teamId=%d" % tid, t2)
    check("已读后未读数归零", (un2.get("data") or 0) == 0, un2.get("data"))

    print("\n=== D. 附件上传 / 下载 / 撤回 ===")
    payload = "CoBot 冒烟测试附件内容\n".encode("utf-8")
    _, up = upload(t1, tid, "冒烟附件.txt", payload)
    vo = up.get("data") or {}
    check("上传附件", up.get("code") == 200, up.get("msg"))
    check("附件返回 filePath/msgType", bool(vo.get("filePath")) and vo.get("msgType") == "file", vo)

    _, s2 = call("POST", "/api/chat/send", t1, {
        "teamId": tid, "content": "", "msgType": vo.get("msgType"),
        "fileName": vo.get("fileName"), "filePath": vo.get("filePath"), "fileSize": vo.get("fileSize")})
    check("发布附件消息", s2.get("code") == 200, s2.get("msg"))

    _, lst2 = call("GET", "/api/chat/list?teamId=%d&afterId=%d" % (tid, after_id), t2)
    files = [m for m in (lst2.get("data") or []) if m.get("msgType") == "file"]
    check("聊天列表含文件消息", len(files) > 0, [(m["id"], m.get("fileName")) for m in files])
    if files:
        fid = files[-1]["id"]
        st, blob = raw("GET", "/api/chat/file/%d" % fid, t2)
        check("附件下载内容一致", st == 200 and blob == payload, (st, len(blob)))
        _, rv = call("POST", "/api/chat/revoke", t1, {"messageId": fid})
        check("负责人撤回消息", rv.get("code") == 200, rv.get("msg"))
        _, lst3 = call("GET", "/api/chat/list?teamId=%d&afterId=0" % tid, t1)
        rev = [m for m in (lst3.get("data") or []) if m["id"] == fid]
        check("撤回后 revoked=1", rev and rev[0].get("revoked") == 1, rev[:1])
        check("撤回消息仍带发送时间（前端气泡要显示时间）",
              bool(rev and rev[0].get("createTime")), rev[0].get("createTime") if rev else None)

    print("\n=== E. 任务看板 ===")
    _, tl = call("GET", "/api/task/list?teamId=%d" % tid, t1)
    check("任务列表可读", tl.get("code") == 200, len(tl.get("data") or []))
    st, xls = raw("GET", "/api/task/export?teamId=%d" % tid, t1)
    check("看板导出真实 xlsx", st == 200 and xls[:2] == b"PK" and len(xls) > 2000, (st, len(xls)))

    print("\n=== F. AI 扩展能力 ===")
    _, mt = call("POST", "/api/ai/meeting", t1, {
        "teamId": tid,
        "notes": "本周完成登录接口联调（张三，周五前）；李四整理接口文档，下周三交付；王五负责压测报告，9月30日完成"})
    mdata = mt.get("data") or {}
    check("会议纪要拆任务", mt.get("code") == 200 and len(mdata.get("tasks") or []) >= 2,
          (mt.get("code"), len(mdata.get("tasks") or []), mdata.get("aiGenerated"), mdata.get("fallbackNote")))
    if mdata.get("artifactId"):
        _, im = call("POST", "/api/ai/meeting/import", t1, {"teamId": tid, "artifactId": mdata["artifactId"]})
        check("纪要任务导入看板", im.get("code") == 200, im.get("data") or im.get("msg"))

    _, wk = call("POST", "/api/ai/weekly", t1, {"teamId": tid})
    wdata = wk.get("data") or {}
    check("生成团队周报", wk.get("code") == 200 and len(str(wdata.get("report") or "")) > 50,
          (wk.get("code"), len(str(wdata.get("report") or "")), wdata.get("aiGenerated")))
    if wdata.get("artifactId"):
        _, pu = call("POST", "/api/ai/weekly/push", t1, {"teamId": tid, "artifactId": wdata["artifactId"]})
        check("周报推送到聊天室", pu.get("code") == 200, pu.get("data") or pu.get("msg"))

    _, ak = call("POST", "/api/ai/ask", t1, {"teamId": tid, "question": "现在有几条逾期任务？"})
    check("团队数据问答", ak.get("code") == 200 and bool((ak.get("data") or {}).get("answer")),
          (ak.get("code"), str((ak.get("data") or {}).get("answer"))[:60]))

    print("\n=== G. 团队设置与越权防护 ===")
    _, ri = call("POST", "/api/team/reset-invite", t1, {"teamId": tid, "inviteExpireHours": 24, "inviteMaxUse": 5})
    d = ri.get("data") or {}
    check("重置邀请码（带有效期/次数）", ri.get("code") == 200 and bool(d.get("inviteCode")),
          (d.get("inviteCode"), d.get("inviteExpireAt"), d.get("inviteMaxUse")))
    _, nt = call("POST", "/api/team/notice", t1, {"teamId": tid, "notice": "冒烟测试公告"})
    check("设置团队公告", nt.get("code") == 200, nt.get("data") or nt.get("msg"))
    _, as_ = call("POST", "/api/team/reset-invite", t2, {"teamId": tid, "inviteExpireHours": 1})
    check("成员无权重置邀请码（越权防护）", as_.get("code") != 200, as_.get("msg"))

    if created:
        _, dis = call("POST", "/api/team/dismiss", t1, {"teamId": tid})
        check("清理测试团队", dis.get("code") == 200, dis.get("data") or dis.get("msg"))
    else:
        print("  [SKIP] 测试团队为已存在团队，未做清理")

    passed = sum(1 for _, ok, _ in results if ok)
    print("\n==== 冒烟结果：%d/%d 通过 ====" % (passed, len(results)))
    for n, ok, d in results:
        if not ok:
            print("  FAILED -> " + n + " : " + str(d))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
