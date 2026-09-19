# -*- coding: utf-8 -*-
"""
本地 MySQL 操作小工具（避免 shell 重定向 / 编码问题）

用法：
    python dbtool.py exec <sql文件路径>      # 执行 SQL 文件
    python dbtool.py query "<SQL>"            # 执行查询并打印结果
"""
import subprocess
import sys

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
BASE_ARGS = [MYSQL, "-uroot", "-p", "--default-character-set=utf8mb4"]


def exec_file(path):
    """执行 SQL 文件（按字节传入，避免编码转换问题）"""
    with open(path, "rb") as f:
        raw = f.read()
    proc = subprocess.run(BASE_ARGS, input=raw, capture_output=True)
    return (proc.returncode,
            proc.stdout.decode("utf-8", "replace"),
            proc.stderr.decode("utf-8", "replace"))


def query(sql, database="cobot_db"):
    """执行单条/多条查询，返回 mysql 默认的表格文本"""
    proc = subprocess.run(BASE_ARGS + [database, "-e", sql], capture_output=True)
    return (proc.returncode,
            proc.stdout.decode("utf-8", "replace"),
            proc.stderr.decode("utf-8", "replace"))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    mode, arg = sys.argv[1], sys.argv[2]
    if mode == "exec":
        code, out, err = exec_file(arg)
    else:
        code, out, err = query(arg)
    if out.strip():
        print(out)
    if err.strip():
        print("[STDERR]", err)
    print("[EXIT]", code)
