#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CoBot-v2 账号体系改造 · QA 独立回归（严过关）

覆盖（独立设计，不复用工程师自验用例）：
  A. 账号唯一性：重复注册 / 并发竞态 / 空格规范化 / 大小写 / nickname 回落
  B. 无团队隔离：新账号不得读到 teamId=1 的任何数据（逐接口攻击）；张三反向确认可用
  C. 登录：老账号可用 / 错密码提示 / 防爆破锁定 / user/list 不泄露 account+password

运行前置：应用已监听 8093；MySQL 可连（root/123456）。
本脚本不修改业务源码；测试账号统一前缀 qa_ ，便于事后清理。
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "http://127.0.0.1:8093"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
PASS = "CHANGE_ME"
TS = str(int(time.time()))
# 本地回环绕过系统代理（否则请求会被 HTTP_PROXY 劫持成 502）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("  " + str(detail) if detail else ""))


def http(method, path, body=None, token=None, timeout=60):
    headers = {}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}


def login(account, password=PASS):
    return http("POST", "/api/auth/login", {"account": account, "password": password})


def register(account, nickname=None, password=PASS):
    body = {"account": account, "password": password}
    if nickname is not None:
        body["nickname"] = nickname
    return http("POST", "/api/auth/register", body)


def db(sql):
    p = subprocess.run([MYSQL, "--default-character-set=utf8mb4", "-uroot", "-p",
                        "cobot_db", "-N", "-B", "-e", sql], capture_output=True)
    return p.stdout.decode("utf-8", "replace").strip()


def count_account(account):
    out = db("SELECT COUNT(*) FROM sys_user WHERE account = '%s'" % account.replace("'", "''"))
    try:
        return int(out)
    except Exception:
        return -1


def main():
    print("测试批次 TS = %s" % TS)
    # 清掉本批次可能残留
    db("DELETE FROM sys_user WHERE account LIKE 'qa\\_%'")

    print("\n=== A1. 重复注册同一 account（应用层预检）===")
    acc = "qa_dup_" + TS
    st, r1 = register(acc, "重复一号")
    check("首次注册成功", r1.get("code") == 200, r1.get("msg"))
    st, r2 = register(acc, "重复二号")
    check("再次注册同一 account 被拒", r2.get("code") != 200, r2.get("msg"))
    c = count_account(acc)
    check("库中该 account 仅 1 条", c == 1, "count=%s" % c)

    print("\n=== A2. 并发注册同一 account（5 线程同时打，验唯一索引兜竞态）===")
    race = "qa_race_" + TS

    def reg_once(_):
        return register(race, "并发同学")

    with ThreadPoolExecutor(max_workers=5) as ex:
        outs = list(ex.map(reg_once, range(5)))
    codes = [(r.get("code")) for _, r in outs]
    ok_n = sum(1 for c2 in codes if c2 == 200)
    check("5 个并发注册只有 1 个成功", ok_n == 1, "success=%d codes=%s" % (ok_n, codes))
    c = count_account(race)
    check("库中该 account 仅 1 条", c == 1, "count=%s" % c)

    print("\n=== A3. 空格规范化（注册 '20239998' 后再注册 ' 20239998 '）===")
    db("DELETE FROM sys_user WHERE account IN ('20239998', ' 20239998 ')")
    st, r1 = register("20239998", "空格同学")
    check("注册 '20239998' 成功", r1.get("code") == 200, r1.get("msg"))
    st, r2 = register(" 20239998 ", "空格同学2")
    check("注册 ' 20239998 '（前后带空格）被拒", r2.get("code") != 200, r2.get("msg"))
    c = count_account("20239998")
    check("库中 '20239998' 仅 1 条", c == 1, "count=%s" % c)

    print("\n=== A4. 大小写（注册 'abc123' 后再注册 'ABC123'）===")
    db("DELETE FROM sys_user WHERE account IN ('abc123', 'ABC123')")
    st, r1 = register("abc123", "小写同学")
    check("注册 'abc123' 成功", r1.get("code") == 200, r1.get("msg"))
    st, r2 = register("ABC123", "大写同学")
    check("注册 'ABC123' 被拒（DB 排序规则不区分大小写）", r2.get("code") != 200, r2.get("msg"))
    c = count_account("ABC123")
    check("库中 'ABC123'（不分大小写匹配）仍为 1 条", c == 1, "count=%s" % c)

    print("\n=== A5. nickname 留空回落 account ===")
    nick = "qa_nick_" + TS
    st, r1 = register(nick, None)   # 不传 nickname
    check("注册（不传 nickname）成功", r1.get("code") == 200, r1.get("msg"))
    uname = db("SELECT username FROM sys_user WHERE account = '%s'" % nick)
    check("库中 username 回落成 account", uname == nick, "username=%s account=%s" % (uname, nick))

    print("\n=== B. 无团队隔离（新注册账号不得触碰 teamId=1 任何数据）===")
    iso = "qa_iso_" + TS
    st, r1 = register(iso, "隔离同学")
    check("注册隔离账号", r1.get("code") == 200, r1.get("msg"))
    st, lr = login(iso)
    tok = (lr.get("data") or {}).get("token")
    check("隔离账号登录成功", bool(tok), lr.get("msg"))
    st, my = http("GET", "/api/team/my", token=tok)
    check("GET /api/team/my 返回空数组", my.get("code") == 200 and (my.get("data") or []) == [],
          my.get("data"))

    attacks = [
        ("GET", "/api/chat/list?teamId=1"),
        ("POST", "/api/chat/send", {"teamId": 1, "content": "越权入侵测试"}),
        ("GET", "/api/chat/unread?teamId=1"),
        ("GET", "/api/task/list?teamId=1"),
        ("GET", "/api/task/pending?teamId=1"),
        ("GET", "/api/ai/insight?teamId=1"),
        ("GET", "/api/ai/history?teamId=1"),
        ("GET", "/api/user/list?teamId=1"),
        ("GET", "/api/team/members?teamId=1"),
    ]
    for item in attacks:
        m, p = item[0], item[1]
        b = item[2] if len(item) > 2 else None
        st, rr = http(m, p, body=b, token=tok)
        check("隔离账号被拒 %s %s" % (m, p), rr.get("code") != 200,
              "code=%s msg=%s" % (rr.get("code"), rr.get("msg")))

    print("\n=== B-rev. 反向确认：张三（团队1负责人）调读接口必须正常 ===")
    st, zr = login("20230001")
    ztok = (zr.get("data") or {}).get("token")
    check("张三登录成功", bool(ztok), zr.get("msg"))
    st, zmy = http("GET", "/api/team/my", token=ztok)
    check("张三 team/my 非空", zmy.get("code") == 200 and len(zmy.get("data") or []) > 0,
          [t.get("teamName") for t in (zmy.get("data") or [])])
    for m, p in [("GET", "/api/chat/list?teamId=1"), ("GET", "/api/chat/unread?teamId=1"),
                 ("GET", "/api/task/list?teamId=1"), ("GET", "/api/task/pending?teamId=1"),
                 ("GET", "/api/ai/insight?teamId=1"), ("GET", "/api/ai/history?teamId=1"),
                 ("GET", "/api/user/list?teamId=1"), ("GET", "/api/team/members?teamId=1")]:
        st, rr = http(m, p, token=ztok)
        check("张三可用 %s" % p, rr.get("code") == 200, rr.get("msg"))

    print("\n=== C1. 老账号登录 + 显示名未被破坏 ===")
    st, r = login("20230001")
    check("老账号 account=20230001 登录成功", r.get("code") == 200, r.get("msg"))
    check("登录响应 username 仍为「张三」", (r.get("data") or {}).get("username") == "张三",
          (r.get("data") or {}).get("username"))

    print("\n=== C2. 错误密码提示（用临时账号，避免污染张三）===")
    bf = "qa_bf_" + TS
    st, r = register(bf, "爆破同学")
    check("准备爆破临时账号", r.get("code") == 200, r.get("msg"))
    st, r = login(bf, "definitely-wrong")
    check("错误密码提示「账号或密码错误」",
          r.get("code") != 200 and "账号或密码错误" in str(r.get("msg")), r.get("msg"))

    print("\n=== C3. 防爆破：同账号连续 5 次错密码，第 6 次应锁定 ===")
    for i in range(4):   # 上面已错 1 次，这里再补 4 次，共 5 次
        st, rr = login(bf, "definitely-wrong")
    st, r6 = login(bf, "definitely-wrong")
    check("第 6 次返回锁定提示", "锁定" in str(r6.get("msg")), r6.get("msg"))

    print("\n=== C4. /api/user/list 不泄露 account / password 且含 username ===")
    st, r = http("GET", "/api/user/list?teamId=1", token=ztok)
    raw = json.dumps(r, ensure_ascii=False)
    check("响应不含 account 字段", '"account"' not in raw, raw[:160])
    check("响应不含 password 字段", '"password"' not in raw, raw[:160])
    check("响应含 username 字段", '"username"' in raw, raw[:160])

    passed = sum(1 for _, ok, _ in results if ok)
    print("\n==== 账号体系独立回归：%d/%d 通过 ====" % (passed, len(results)))
    for n, ok, d in results:
        if not ok:
            print("  FAILED -> " + n + " : " + str(d))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
