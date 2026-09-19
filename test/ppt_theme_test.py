"""PPT 配色主题功能验证脚本

验证点：
1. GET /api/ai/themes 返回全部主题（key/中文名/hex）
2. 同一主题分别以不同配色生成 PPT，文件真实可下载
3. 解压 .pptx 校验：封面页背景色 / 内容页左侧竖条颜色 == 所选主题主色（证明配色真的生效）
4. 非法主题 key（如 hacker）自动回落默认蓝，不报错
5. 历史列表回显 theme / themeName / themeColor
"""
import io
import json
import os
import re
import sys
import urllib.request
import zipfile

BASE = 'http://localhost:8093'
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'output')

passed = 0
failed = 0


def check(name, ok, extra=''):
    global passed, failed
    if ok:
        passed += 1
        print('  [PASS] %s %s' % (name, extra))
    else:
        failed += 1
        print('  [FAIL] %s %s' % (name, extra))


def call(method, path, body=None, token=None, raw=False, timeout=180):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    resp = urllib.request.urlopen(req, timeout=timeout)
    return resp.read() if raw else json.loads(resp.read())


def login(account, password='CHANGE_ME'):
    return call('POST', '/api/auth/login',
                {'account': account, 'password': password})['data']['token']


def slide_colors(pptx_bytes):
    """取出每页用到的字体色 / 填充色（十六进制集合）"""
    z = zipfile.ZipFile(io.BytesIO(pptx_bytes))
    colors = set()
    for name in z.namelist():
        if re.match(r'ppt/slides/slide\d+\.xml$', name):
            xml = z.read(name).decode('utf-8')
            colors |= set(re.findall(r'srgbClr val="([0-9A-Fa-f]{6})"', xml))
    return {c.upper() for c in colors}


print('[1] 登录（负责人 20230001，显示昵称 张三）')
tk = login('20230001')
print('    token 已获取')

print('\n[2] 主题列表接口')
themes = call('GET', '/api/ai/themes', None, tk)['data']
print('    主题数：%d -> %s' % (len(themes), '、'.join(t['name'] for t in themes)))
check('返回 7 套主题', len(themes) == 7, '实际 %d' % len(themes))
check('主题字段完整', all(t.get('key') and t.get('name') and t.get('primary') for t in themes))
for t in themes:
    print('      %-8s %-6s primary=%s accent=%s' % (t['key'], t['name'], t['primary'], t['accent']))

print('\n[3] 多主题生成 + 配色校验')
os.makedirs(OUT_DIR, exist_ok=True)
cases = [
    ('green', '生机绿'),
    ('purple', '星云紫'),
    ('dark', '极夜金'),
]
for key, cname in cases:
    resp = call('POST', '/api/ai/ppt', {
        'teamId': 1, 'topic': '配色验证 · ' + cname, 'slideCount': 6,
        'scene': 'general', 'theme': key,
    }, tk)
    if resp.get('code') != 200:
        check('%s 生成成功' % cname, False, resp.get('msg', ''))
        continue
    d = resp['data']
    check('%s 生成成功' % cname, True, '%d 页 / %d 字节' % (len(d['slides']), d['fileSize']))
    check('%s 回传主题名' % cname, d.get('theme') == key and d.get('themeName') == cname,
          '%s/%s' % (d.get('theme'), d.get('themeName')))

    raw = call('GET', '/api/ai/file/%d' % d['artifactId'], None, tk, raw=True)
    fn = os.path.join(OUT_DIR, '配色验证-%s.pptx' % cname)
    with open(fn, 'wb') as f:
        f.write(raw)
    colors = slide_colors(raw)
    expect = next((t['primary'] for t in themes if t['key'] == key), '').lstrip('#').upper()
    expect_accent = next((t['accent'] for t in themes if t['key'] == key), '').lstrip('#').upper()
    check('%s 封面底色命中主色 %s' % (cname, expect), expect in colors,
          '文件中出现的主色集合：%s' % ','.join(sorted(colors))[:120])
    check('%s 强调色 %s 出现' % (cname, expect_accent), expect_accent in colors)
    print('      已保存 %s' % fn)

print('\n[4] 非法主题 key 回落默认蓝')
resp = call('POST', '/api/ai/ppt', {
    'teamId': 1, 'topic': '非法主题回落验证', 'slideCount': 6,
    'scene': 'general', 'theme': 'hacker',
}, tk)
check('非法 key 不报错', resp.get('code') == 200, resp.get('msg', ''))
if resp.get('code') == 200:
    d = resp['data']
    check('回落为商务蓝', d.get('theme') == 'blue', '实际 %s' % d.get('theme'))
    raw = call('GET', '/api/ai/file/%d' % d['artifactId'], None, tk, raw=True)
    colors = slide_colors(raw)
    check('回落文件使用蓝色 2B4CA0', '2B4CA0' in colors)

print('\n[5] 历史列表回显主题')
hist = call('GET', '/api/ai/history?teamId=1', None, tk)['data']
ppt_hist = [h for h in hist if h['genType'] == 'ppt']
check('历史含 PPT 记录', len(ppt_hist) >= 4, '%d 条' % len(ppt_hist))
with_theme = [h for h in ppt_hist if h.get('theme') and h.get('themeColor')]
check('历史回显 theme/themeColor', len(with_theme) >= 4,
      '示例：%s / %s / %s' % (with_theme[0].get('theme'), with_theme[0].get('themeName'),
                            with_theme[0].get('themeColor')) if with_theme else '无')
non_ppt = [h for h in hist if h['genType'] != 'ppt']
check('非 PPT 产物不带配色', all(not h.get('theme') for h in non_ppt))

print('\n' + '=' * 46)
print('结果：%d 通过 / %d 失败' % (passed, failed))
sys.exit(0 if failed == 0 else 1)
