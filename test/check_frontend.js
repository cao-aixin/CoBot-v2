#!/usr/bin/env node
/**
 * CoBot-v2 前端（单文件 Vue CDN 应用）静态自检
 *
 * 背景
 * ----
 * src/main/resources/static/index.html 是"一个文件装下整个前端"的写法：
 * 模板、脚本、样式都在一起，没有构建步骤，写错了编辑器不会报错，
 * 只能等浏览器打开才发现白屏。这个脚本把三类最常见的低级错误提前拦下来。
 *
 * 检查项
 * ------
 * 1. 内联 <script> 的 JS 语法（用 vm.Script 编译，等价 node --check）
 * 2. 模板能否被 Vue 官方编译器编译（标签闭合、指令写法、表达式语法）
 * 3. 模板里用到的变量/方法是否都从 setup() 的 return 里导出了
 *    —— 漏导出是这种单文件写法最容易犯的错，表现是"按钮点了没反应"或整页空白
 *
 * 用法
 * ----
 *     node test/check_frontend.js                        # 检查默认的 static/index.html
 *     node test/check_frontend.js <某个 html 路径>        # 检查指定文件
 *
 * 依赖
 * ----
 * 优先用本地的 @vue/compiler-dom；没有就自动下载官方 global 构建到 .verify-cache/（首次联网一次）。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const HTML = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(ROOT, 'src', 'main', 'resources', 'static', 'index.html');
const CACHE_DIR = path.join(ROOT, '.verify-cache');
const CACHE_FILE = path.join(CACHE_DIR, 'compiler-dom.global.js');
const CDN = 'https://unpkg.com/@vue/compiler-dom@3.5.42/dist/compiler-dom.global.js';

let failed = 0;
const ok = (m) => console.log('  [PASS] ' + m);
const bad = (m) => { failed++; console.log('  [FAIL] ' + m); };

// 模板正则会把 Element Plus 的属性名误当标识符，这里列白名单避免误报
const TEMPLATE_BUILTINS = new Set([]);   // 目前实测无需白名单；若将来出现框架属性误报，再往这里加
const JS_GLOBALS = new Set([
  'true', 'false', 'null', 'undefined', 'new', 'typeof', 'in', 'of', 'return',
  'if', 'else', 'function', 'instanceof', 'delete', 'void', 'this',
  'Math', 'Date', 'JSON', 'Number', 'String', 'Object', 'Array', 'Boolean',
  'parseInt', 'parseFloat', 'isNaN', 'encodeURIComponent', 'decodeURIComponent'
]);

function main() {
  const html = fs.readFileSync(HTML, 'utf8');

  // ---------- 切分模板与脚本 ----------
  const appStart = html.indexOf('<div id="app">');
  const scriptTag = html.indexOf('<script>', appStart);
  if (appStart < 0 || scriptTag < 0) {
    console.log('未找到 #app 根节点或内联 <script>，文件结构可能被改动了');
    return 2;
  }
  const template = html.slice(appStart, scriptTag);
  const scriptEnd = html.indexOf('</script>', scriptTag);
  const script = html.slice(scriptTag + '<script>'.length, scriptEnd);
  console.log('模板 %d 字符 / 脚本 %d 字符', template.length, script.length);

  // ---------- 1. JS 语法 ----------
  console.log('\n[1/3] 内联脚本语法');
  try {
    new vm.Script(script, { filename: 'index-inline.js' });
    ok('脚本语法正确');
  } catch (e) {
    bad('脚本语法错误：' + e.message);
  }

  // ---------- 2. 模板编译 ----------
  console.log('\n[2/3] Vue 模板编译');
  try {
    const { compile } = loadCompilerDom();
    const errors = [];
    compile(template, {
      mode: 'function',
      onError: (e) => errors.push(e),
      onWarn: (w) => errors.push(w)
    });
    if (errors.length) {
      bad('模板编译报错 ' + errors.length + ' 条：');
      for (const e of errors.slice(0, 20)) {
        const loc = e.loc && e.loc.start ? (' @L' + e.loc.start.line + ':' + e.loc.start.column) : '';
        console.log('        - [' + (e.code || 'ERR') + '] ' + (e.message || e) + loc);
      }
    } else {
      ok('模板编译通过，无错误无警告');
    }
  } catch (e) {
    bad('模板检查异常：' + e.message);
  }

  // ---------- 3. 导出一致性 ----------
  console.log('\n[3/3] 模板引用 vs setup() 导出');
  const exported = parseExportedKeys(script);
  const used = collectTemplateIdentifiers(template);
  const missing = [...used].filter((n) =>
    !exported.has(n) && !JS_GLOBALS.has(n) && !TEMPLATE_BUILTINS.has(n));
  if (missing.length) {
    bad('模板用了但没从 setup() 导出：' + missing.join(', '));
  } else {
    ok('模板引用均已导出（共导出 ' + exported.size + ' 项）');
  }

  console.log('\n' + (failed === 0 ? '自检通过' : '自检失败，共 ' + failed + ' 项'));
  return failed === 0 ? 0 : 1;
}

/** 取 Vue 官方编译器：先找本地依赖，找不到就下载 global 构建并用 vm 求值 */
function loadCompilerDom() {
  try {
    return require('@vue/compiler-dom');
  } catch (e) {
    // 本地没装依赖，走 global 构建
  }
  if (!fs.existsSync(CACHE_FILE)) {
    console.log('  本地无 @vue/compiler-dom，下载到 .verify-cache/ …');
    fs.mkdirSync(CACHE_DIR, { recursive: true });
    const buf = fetchSync(CDN);
    fs.writeFileSync(CACHE_FILE, buf);
  }
  const src = fs.readFileSync(CACHE_FILE, 'utf8');
  const sandbox = {
    console, setTimeout, clearTimeout, Date, Math, JSON, Object, Array,
    String, Number, RegExp, Error, Map, Set, Symbol, TextDecoder
  };
  // 浏览器版编译器解析插值时会用 document.createElement 解码 HTML 实体，
  // 这里给"原样返回"的最小桩，只为让编译器跑起来（自检不关心实体解码结果）
  sandbox.document = {
    createElement: () => {
      const el = {
        _raw: '',
        children: [],
        getAttribute: () => {
          const mm = /foo="([\s\S]*)"/.exec(el._raw);
          return mm ? mm[1] : el._raw;
        },
        set innerHTML(v) { el._raw = v; el.children = [el]; },
        get innerHTML() { return el._raw; },
        get textContent() { return el._raw; }
      };
      return el;
    }
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  const mod = vm.runInContext(
    src + '\n;typeof VueCompilerDOM!=="undefined"?VueCompilerDOM:null', sandbox);
  if (!mod || typeof mod.compile !== 'function') throw new Error('compiler-dom 加载失败');
  return mod;
}

/** 用子进程做一次同步 HTTP 下载（本脚本是 CJS，用不了顶层 await） */
function fetchSync(url) {
  const { execFileSync } = require('child_process');
  const out = path.join(CACHE_DIR, '.download.tmp');
  const code = 'fetch(process.argv[1]).then(r=>r.arrayBuffer()).then(b=>' +
               'require("fs").writeFileSync(process.argv[2],Buffer.from(b)))';
  execFileSync(process.execPath, ['-e', code, url, out], { stdio: ['ignore', 'ignore', 'inherit'] });
  const buf = fs.readFileSync(out);
  fs.unlinkSync(out);
  return buf;
}

/** 解析 setup() 中 return { ... } 的键名 */
function parseExportedKeys(js) {
  const keys = new Set();
  const m = /return \{([\s\S]*?)\n\s{4}\};/.exec(js);
  if (!m) return keys;
  for (const line of m[1].split('\n')) {
    const code = line.split('//')[0];
    for (let part of code.split(',')) {
      part = part.trim();
      if (!part) continue;
      if (/^[A-Za-z_$][\w$]*$/.test(part)) { keys.add(part); continue; }
      const kv = /^([A-Za-z_$][\w$]*)\s*:/.exec(part);
      if (kv) keys.add(kv[1]);
    }
  }
  return keys;
}

/** 收集模板中出现的"根标识符"（去掉属性访问、字符串字面量、对象键、v-for/箭头函数局部变量） */
function collectTemplateIdentifiers(tpl) {
  const exprs = [];
  const patterns = [
    /@[\w.]+="([^"]*)"/g,
    /:[\w-]+="([^"]*)"/g,
    /v-model(?:\.\w+)*="([^"]*)"/g,
    /v-(?:if|else-if|show|html|text)="([^"]*)"/g,
    /v-for="([^"]*)"/g,
    /\{\{([\s\S]*?)\}\}/g
  ];
  for (const re of patterns) {
    let m;
    while ((m = re.exec(tpl)) !== null) exprs.push(m[1]);
  }
  // 局部变量：v-for 循环变量、内联箭头函数形参（@command="cmd => f(cmd, m)"）、
  // 以及插槽作用域解构（<template #default="{ row }">）—— 三者都是编译后 render 函数的形参
  const locals = new Set();
  let mv;
  const reFor = /v-for="([^"]*)"/g;
  while ((mv = reFor.exec(tpl)) !== null) {
    for (const n of (mv[1].split(' in ')[0].match(/[A-Za-z_$][\w$]*/g) || [])) locals.add(n);
  }
  const reSlot = /(?:#[\w.-]+|v-slot[\w:-]*?)="([^"]*)"/g;
  while ((mv = reSlot.exec(tpl)) !== null) {
    for (const n of (mv[1].match(/[A-Za-z_$][\w$]*/g) || [])) locals.add(n);
  }
  for (const e of exprs) {
    const reArrow = /(?:\(([^()]*)\)|([A-Za-z_$][\w$]*))\s*=>/g;
    let ma;
    while ((ma = reArrow.exec(e)) !== null) {
      for (const n of ((ma[1] || ma[2] || '').match(/[A-Za-z_$][\w$]*/g) || [])) locals.add(n);
    }
  }
  const used = new Set();
  for (let e of exprs) {
    e = e.replace(/'(?:[^'\\]|\\.)*'/g, ' ')      // 单引号字符串
         .replace(/"(?:[^"\\]|\\.)*"/g, ' ')      // 双引号字符串
         .replace(/\?\./g, ' ')                   // 可选链
         .replace(/\.\s*[A-Za-z_$][\w$]*/g, ' ')  // 属性访问
         .replace(/\{[^{}]*\}/g, (obj) =>          // 对象字面量：只保留取值部分，丢掉键名
            obj.replace(/[A-Za-z_$][\w$]*\s*:(?!:)/g, ' '));
    for (const n of (e.match(/[A-Za-z_$][\w$]*/g) || [])) {
      if (!locals.has(n)) used.add(n);
    }
  }
  return used;
}

process.exit(main());
