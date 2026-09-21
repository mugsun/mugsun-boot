#!/usr/bin/env node
/**
 * 可选模块勾选拼包 CLI（二期下载器）。
 *
 * 用法：
 *   node scripts/module-assemble.mjs --combo basic|full|gis-only|track-only
 *   node scripts/module-assemble.mjs --modules gis,track
 *   node scripts/module-assemble.mjs --combo basic --out /tmp/mugsun-basic --zip
 *
 * 约定：从 sibling 布局读取源（mugsun-boot / mugsun-pc），输出到 --out；
 * 不维护 lite 分支，只拷贝 catalog 中的路径并改 pom / .env / package.json。
 */
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const BOOT_ROOT = path.resolve(__dirname, '..')
const MUGSUN_ROOT = path.resolve(BOOT_ROOT, '..')
const DEFAULT_PC = path.join(MUGSUN_ROOT, 'mugsun-pc')
const CATALOG = path.join(BOOT_ROOT, 'docs/module-catalog.yaml')

function usage(code = 1) {
  console.error(`Usage:
  node scripts/module-assemble.mjs --combo <basic|full|gis-only|track-only> [--out DIR] [--zip]
  node scripts/module-assemble.mjs --modules [gis][,track] [--out DIR] [--zip]
  node scripts/module-assemble.mjs --list
Options:
  --boot <path>   boot 源目录（默认本仓）
  --pc <path>     pc 源目录（默认 ../mugsun-pc）
  --out <path>    输出目录（默认 /tmp/mugsun-assemble-<combo>-<ts>）
  --zip           额外打 zip（与 out 同级）
  --dry-run       只打印计划，不写盘`)
  process.exit(code)
}

function parseArgs(argv) {
  const out = {
    combo: null,
    modules: null,
    boot: BOOT_ROOT,
    pc: DEFAULT_PC,
    out: null,
    zip: false,
    dryRun: false,
    list: false
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const next = () => argv[++i]
    if (a === '--combo') out.combo = next()
    else if (a === '--modules') out.modules = (next() || '').split(',').map((s) => s.trim()).filter(Boolean)
    else if (a === '--boot') out.boot = path.resolve(next())
    else if (a === '--pc') out.pc = path.resolve(next())
    else if (a === '--out') out.out = path.resolve(next())
    else if (a === '--zip') out.zip = true
    else if (a === '--dry-run') out.dryRun = true
    else if (a === '--list' || a === '-h' || a === '--help') out.list = true
    else usage()
  }
  return out
}

function loadCatalog(file) {
  const r = spawnSync('ruby', ['-ryaml', '-rjson', '-e', 'puts JSON.generate(YAML.load_file(ARGV[0]))', file], {
    encoding: 'utf8'
  })
  if (r.status !== 0) {
    throw new Error(`解析 catalog 失败: ${r.stderr || r.stdout}`)
  }
  return JSON.parse(r.stdout)
}

function resolveModules(catalog, args) {
  if (args.modules) {
    for (const id of args.modules) {
      if (!catalog.modules[id] && id !== 'gis-track-heat') {
        throw new Error(`未知模块: ${id}`)
      }
    }
    return args.modules.filter((id) => id === 'gis' || id === 'track')
  }
  if (args.combo) {
    const c = (catalog.combinations || []).find((x) => x.id === args.combo)
    if (!c) throw new Error(`未知组合: ${args.combo}`)
    return [...(c.modules || [])]
  }
  usage()
}

function rmrf(p) {
  fs.rmSync(p, { recursive: true, force: true })
}

function mkdirp(p) {
  fs.mkdirSync(p, { recursive: true })
}

function copyPath(src, dest, { dryRun }) {
  if (!fs.existsSync(src)) {
    throw new Error(`源路径不存在: ${src}`)
  }
  console.log(`  copy ${src} -> ${dest}`)
  if (dryRun) return
  mkdirp(path.dirname(dest))
  fs.cpSync(src, dest, { recursive: true, dereference: false })
}

function writeText(file, content, { dryRun }) {
  console.log(`  write ${file}`)
  if (dryRun) return
  mkdirp(path.dirname(file))
  fs.writeFileSync(file, content)
}

function patchBootParentPom(pomPath, selected, { dryRun }) {
  let text = fs.readFileSync(pomPath, 'utf8')
  const mods = ['mugsun-boot-core', ...selected.map((id) => `mugsun-boot-${id}`), 'mugsun-boot-server']
  const block = mods.map((m) => `\t\t<module>${m}</module>`).join('\n')
  text = text.replace(/<modules>[\s\S]*?<\/modules>/, `<modules>\n${block}\n\t</modules>`)
  writeText(pomPath, text, { dryRun })
}

function patchBootServerPom(pomPath, selected, { dryRun }) {
  let text = fs.readFileSync(pomPath, 'utf8')
  const wantGis = selected.includes('gis')
  const wantTrack = selected.includes('track')
  // 重写 full profile 依赖，仅保留勾选模块
  const deps = []
  if (wantGis) {
    deps.push(`\t\t\t\t<dependency>
\t\t\t\t\t<groupId>com.mugsun</groupId>
\t\t\t\t\t<artifactId>mugsun-boot-gis</artifactId>
\t\t\t\t</dependency>`)
  }
  if (wantTrack) {
    deps.push(`\t\t\t\t<dependency>
\t\t\t\t\t<groupId>com.mugsun</groupId>
\t\t\t\t\t<artifactId>mugsun-boot-track</artifactId>
\t\t\t\t</dependency>`)
  }
  const fullBody =
    deps.length === 0
      ? `\t\t\t<!-- assemble: no optional modules -->\n`
      : `<dependencies>\n${deps.join('\n')}\n\t\t\t</dependencies>\n`
  text = text.replace(
    /<profile>\s*<id>full<\/id>[\s\S]*?<\/profile>/,
    `<profile>
\t\t\t<id>full</id>
\t\t\t<activation>
\t\t\t\t<activeByDefault>true</activeByDefault>
\t\t\t</activation>
\t\t\t${fullBody}\t\t</profile>`
  )
  writeText(pomPath, text, { dryRun })
}

function patchPcEnv(envPath, selected, catalog, { dryRun }) {
  let text = fs.existsSync(envPath) ? fs.readFileSync(envPath, 'utf8') : ''
  const setFlag = (key, on) => {
    const line = `${key} = ${on ? 'true' : 'false'}`
    if (new RegExp(`^\\s*${key}\\s*=`, 'm').test(text)) {
      text = text.replace(new RegExp(`^\\s*${key}\\s*=.*$`, 'm'), line)
    } else {
      text += `\n${line}\n`
    }
  }
  setFlag('VITE_ENABLE_GIS', selected.includes('gis'))
  setFlag('VITE_ENABLE_TRACK', selected.includes('track'))
  writeText(envPath, text, { dryRun })
}

function patchPcPackageJson(pkgPath, selected, catalog, { dryRun }) {
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'))
  const drop = new Set()
  for (const id of ['gis', 'track']) {
    if (selected.includes(id)) continue
    const mod = catalog.modules[id]
    for (const name of mod?.pc?.npm_optional || []) drop.add(name)
  }
  if (drop.size === 0) {
    console.log('  package.json: 无需剔除可选 npm')
    return
  }
  for (const section of ['dependencies', 'devDependencies', 'optionalDependencies']) {
    if (!pkg[section]) continue
    for (const name of drop) {
      if (pkg[section][name]) {
        console.log(`  package.json: remove ${section}.${name}`)
        delete pkg[section][name]
      }
    }
  }
  writeText(pkgPath, JSON.stringify(pkg, null, 2) + '\n', { dryRun })
}

function removePcOptionalSymlinks(pcOut, selected, { dryRun }) {
  // 历史兼容：若仍残留指向 modules 的 symlink，在未勾选时清掉
  const links = [
    ['gis', 'src/views/gis'],
    ['gis', 'src/api/gis.ts'],
    ['gis', 'src/components/gis'],
    ['gis', 'src/gis'],
    ['track', 'src/views/track'],
    ['track', 'src/api/track.ts']
  ]
  for (const [mod, rel] of links) {
    if (selected.includes(mod)) continue
    const p = path.join(pcOut, rel)
    try {
      const st = fs.lstatSync(p)
      console.log(`  unlink ${p}`)
      if (!dryRun) fs.rmSync(p, { recursive: true, force: true })
    } catch {
      /* ignore */
    }
  }
}

function writeAssembleMeta(outRoot, combo, selected, { dryRun }) {
  const meta = {
    generatedAt: new Date().toISOString(),
    combo: combo || null,
    modules: selected,
    note: 'Generated by module-assemble.mjs from module-catalog.yaml'
  }
  writeText(path.join(outRoot, 'ASSEMBLE.json'), JSON.stringify(meta, null, 2) + '\n', { dryRun })
}

function zipDir(dir, zipPath) {
  const parent = path.dirname(dir)
  const base = path.basename(dir)
  const r = spawnSync('zip', ['-qry', zipPath, base], { cwd: parent, encoding: 'utf8' })
  if (r.status !== 0) throw new Error(r.stderr || 'zip failed')
  console.log(`zip -> ${zipPath}`)
}

function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.list) {
    const catalog = loadCatalog(CATALOG)
    console.log('combinations:')
    for (const c of catalog.combinations || []) {
      console.log(`  - ${c.id}: [${(c.modules || []).join(', ')}]`)
    }
    console.log('modules:')
    for (const [id, m] of Object.entries(catalog.modules || {})) {
      if (m.requires) continue
      console.log(`  - ${id}: ${m.title}`)
    }
    return
  }

  const catalog = loadCatalog(CATALOG)
  const selected = resolveModules(catalog, args)
  const comboName = args.combo || selected.slice().sort().join('+') || 'basic'
  const outRoot =
    args.out || path.join('/tmp', `mugsun-assemble-${comboName}-${Date.now()}`)
  const dry = { dryRun: args.dryRun }

  console.log(`assemble combo=${comboName} modules=[${selected.join(', ')}]`)
  console.log(`boot=${args.boot}`)
  console.log(`pc=${args.pc}`)
  console.log(`out=${outRoot}`)

  if (!args.dryRun) {
    rmrf(outRoot)
    mkdirp(outRoot)
  }

  const bootOut = path.join(outRoot, 'mugsun-boot')
  const pcOut = path.join(outRoot, 'mugsun-pc')

  // --- boot ---
  console.log('\n[boot]')
  mkdirp(bootOut)
  const bootRootFiles = [
    'pom.xml',
    'README.md',
    'README_EN.md',
    'docker-compose.yml',
    'Dockerfile',
    'LICENSE',
    'lombok.config',
    '.gitignore',
    'config',
    'docker',
    'docs',
    'scripts'
  ]
  for (const f of bootRootFiles) {
    const src = path.join(args.boot, f)
    if (!fs.existsSync(src)) continue
    copyPath(src, path.join(bootOut, f), dry)
  }
  for (const mod of catalog.core.boot) {
    copyPath(path.join(args.boot, mod), path.join(bootOut, mod), dry)
  }
  for (const id of selected) {
    const paths = catalog.modules[id]?.boot?.paths || []
    for (const p of paths) {
      copyPath(path.join(args.boot, p), path.join(bootOut, p), dry)
    }
  }
  if (!args.dryRun) {
    patchBootParentPom(path.join(bootOut, 'pom.xml'), selected, dry)
    patchBootServerPom(path.join(bootOut, 'mugsun-boot-server', 'pom.xml'), selected, dry)
  } else {
    console.log('  (dry-run) would patch boot pom.xml / server pom.xml')
  }

  // --- pc ---
  console.log('\n[pc]')
  mkdirp(pcOut)
  const pcRootFiles = [
    'package.json',
    'pnpm-lock.yaml',
    'pnpm-workspace.yaml',
    'tsconfig.json',
    'tsconfig.node.json',
    'vite.config.ts',
    'index.html',
    'README.md',
    '.env',
    '.env.development',
    '.env.production',
    '.gitignore',
    'public',
    'scripts',
    'tests',
    'e2e',
    'types'
  ]
  for (const f of pcRootFiles) {
    const src = path.join(args.pc, f)
    if (!fs.existsSync(src)) continue
    copyPath(src, path.join(pcOut, f), dry)
  }
  copyPath(path.join(args.pc, 'src'), path.join(pcOut, 'src'), dry)
  for (const id of selected) {
    const paths = catalog.modules[id]?.pc?.paths || []
    for (const p of paths) {
      copyPath(path.join(args.pc, p), path.join(pcOut, p), dry)
    }
  }
  if (!args.dryRun) {
    patchPcEnv(path.join(pcOut, '.env'), selected, catalog, dry)
    patchPcPackageJson(path.join(pcOut, 'package.json'), selected, catalog, dry)
    removePcOptionalSymlinks(pcOut, selected, dry)
  } else {
    console.log('  (dry-run) would patch .env / package.json / optional symlinks')
  }

  writeAssembleMeta(outRoot, args.combo, selected, dry)

  if (args.zip && !args.dryRun) {
    zipDir(outRoot, `${outRoot}.zip`)
  }

  console.log('\nOK')
  console.log(`输出目录: ${outRoot}`)
  if (selected.length === 0) {
    console.log('提示: basic 组合无 GIS/埋点源码；boot 用默认 profile 即可（server 无可选依赖）。')
  }
}

main()
