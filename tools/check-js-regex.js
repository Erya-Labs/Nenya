// check-js-regex.js — compile every Regex literal in Nenya's common Kotlin code with
// JavaScript's own regular-expression parser.
//
// Run it with Node, from the repository root:
//
//   node tools/check-js-regex.js nenya-core/src/commonMain nenya-core/src/commonTest
//
// Why this exists. Code in src/commonMain and src/commonTest runs on the JVM *and* on
// JavaScript, and the two platforms do not accept the same regular expressions. Kotlin/JVM
// hands a pattern to java.util.regex; Kotlin/JS hands the same string to JavaScript's RegExp
// with the "u" flag. Some patterns are legal on the first and a SyntaxError on the second —
// an unescaped "]" outside a character class is the one that broke six tests, and there are
// others (lone "{", unknown escapes such as "\-" outside a class, and so on) that "u" mode
// refuses. A JVM test run cannot see any of this, and :nenya-core:compileTestKotlinJs cannot
// either, because it compiles the pattern as an ordinary string; the pattern is only parsed
// when a JavaScript test reaches it, and a pattern held in an object's initialiser takes every
// later access to that object down with it. So this script parses them all directly — not only
// the ones some test happens to reach — with the same flag Kotlin/JS uses.
//
// It needs Node only (no npm package, no network). It exits 0 when every literal compiles,
// 1 when any does not (each one is listed with file, line, pattern and JavaScript's error),
// and 2 when it was given no directory or found nothing to check, so a wrong path can never
// pass by checking zero patterns.
//
// What it recognises: Regex("""...""") raw-string literals, whose content is literal, and
// Regex("...") ordinary-string literals, whose Kotlin escapes are undone before compiling. A
// pattern built at run time from a variable is not a literal and is not seen.
const fs = require("fs");
const path = require("path");

const roots = process.argv.slice(2);
if (roots.length === 0) {
  console.log("usage: node tools/check-js-regex.js <source directory>...");
  process.exit(2);
}

const files = [];
function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (p.endsWith(".kt")) files.push(p);
  }
}
roots.forEach(walk);

let total = 0, bad = 0;
for (const f of files) {
  const src = fs.readFileSync(f, "utf8");
  // Raw strings: Regex("""...""") — content is literal.
  for (const m of src.matchAll(/Regex\(\s*"""([\s\S]*?)"""/g)) check(f, src, m.index, m[1]);
  // Ordinary strings: Regex("...") — Kotlin escapes must be undone first.
  for (const m of src.matchAll(/Regex\(\s*"((?:[^"\\]|\\.)*)"/g)) {
    const unescaped = m[1].replace(/\\(.)/g, (_, c) => ({ n: "\n", t: "\t", r: "\r", "\\": "\\", '"': '"', $: "$" }[c] ?? "\\" + c));
    check(f, src, m.index, unescaped);
  }
}

function check(file, src, index, pattern) {
  total++;
  const line = src.slice(0, index).split("\n").length;
  try {
    new RegExp(pattern, "gu");
  } catch (e) {
    bad++;
    console.log(`  INVALID IN JAVASCRIPT  ${path.relative(process.cwd(), file)}:${line}`);
    console.log(`      pattern: ${pattern}`);
    console.log(`      error:   ${e.message}`);
  }
}

console.log(`\nchecked ${total} regex literals in ${files.length} files — ${bad} invalid in JavaScript`);
if (total === 0) {
  console.log("no regex literal was found at all: the paths given are wrong, and a check of nothing proves nothing");
  process.exit(2);
}
process.exit(bad === 0 ? 0 : 1);
