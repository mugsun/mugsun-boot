# 验证码素材来源

| 目录 | 内容 | 来源 / 许可 |
|---|---|---|
| bg/ | 12 张浅色插画底图（600×300） | 本项目自有素材：体验设计官用 AI 生图工具生成，再经后期处理（缩放、裁切），无第三方素材。见下「生成方式」。**已确认随仓库公开发布，许可证与本仓库一致（Apache License 2.0，见仓库根目录 LICENSE）** |
| rotate/ | 16 张旋转题圆图原图（240×240，服务端裁圆） | 本项目自有素材：体验设计官用 AI 生图工具生成，再经后期处理（缩放、裁切），无第三方素材。见下「生成方式」。**已确认随仓库公开发布，许可证与本仓库一致（Apache License 2.0，见仓库根目录 LICENSE）** |
| icons/ | 44 个线性图标（64×64 PNG）+ manifest.json | gen.py 生成的自绘 SVG（转 PNG），脚本在仓库 `scripts/captcha-icons/gen.py`，可完整重现 PNG 与 manifest.json，随项目许可发布。manifest.json 的 `confusable` 为易混组名，同组图标不同场出现 |
| words.txt | WORD_CLICK 词库：477 条常用词语与成语（3–4 字） | 本项目自行整理，未复制任何受版权保护的词典；只用《通用规范汉字表》一级字，词内无重复字，已人工去掉生僻字及涉政、暴力、色情、违法、歧视类词语；许可证与本仓库一致 |
| confusable-chars.txt | WORD_CLICK 干扰字映射：727 字的形近字 / 同音字 | 本项目自行整理：形近字为人工分组；同音字按普通话读音从一级字中筛出（每字至多 3 个）后人工通读；只含《通用规范汉字表》一级字；许可证与本仓库一致 |
| font/NotoSansSC-Bold-subset.ttf | 思源黑体（Noto Sans CJK SC Bold）子集：覆盖 words.txt 与 confusable-chars.txt 全部 1595 字，转为 TrueType 轮廓，文件 418,424 字节（约 409 KB） | © Google / Adobe，SIL Open Font License 1.1，见 font/OFL.txt；子集为修改版，保留原字体名的保留名限制按 OFL 处理 |

不使用 tianai-captcha 自带素材。

## 生成方式（可追溯性）
- bg/、rotate/：AI 生图工具直接输出的**位图**，没有 SVG 或分层源文件，也没有可重跑的生成脚本；后期处理仅为缩放、裁切（rotate/ 另裁成正方形，服务端运行时再裁圆）。未使用任何第三方照片、图库或他人作品。
- icons/：`scripts/captcha-icons/gen.py` 内手写 SVG 路径，cairosvg 转 64×64 PNG；易混分组 `GROUPS` 同在脚本中，写入 manifest.json 的 `confusable`（服务端只从清单读取）。
- words.txt：本项目自行整理，只用《通用规范汉字表》一级字。
- confusable-chars.txt：本项目自行整理（形近字人工分组 + 同音字按读音筛选后人工通读）。
- 字体：`scripts/captcha-font/subset.py <NotoSansCJK-Bold.ttc|NotoSansCJKsc-Bold.otf> font/NotoSansSC-Bold-subset.ttf captcha/words.txt captcha/confusable-chars.txt` 重新生成（fonttools，子集后 CFF 转 glyf）；改词库或映射后必须重跑，单测会检查两者的每个字都在字体里。OFL 说明见上表。

## 生产环境：建议使用私有图库
本目录随开源仓库发布，图片数量有限（底图 12 张、旋转图 16 张、图标 44 个）：攻击者可以拿公开底图与题图做差、或用拼图块做模板匹配定位缺口，也可以预建旋转/图标答案表。服务端每题会对底图和旋转图做随机缩放、裁切、镜像、调色，拼图块加噪声与色偏，假缺口与真缺口同轮廓，但这只是抬高成本，**生产必须换私有图库**。prod 下仍在用自带素材（按目录或文件 SHA-256 判断）时启动会打 WARN，并通过 `CaptchaAssetCheck` 暴露标志供 doctor 标红。生产部署请换成自己的私有素材目录（`file:` 或 `classpath:` 均可），并定期更换：

```yaml
mugsun.captcha:
  background: { dir: "file:/data/captcha/bg/" }
  rotate:     { dir: "file:/data/captcha/rotate/" }
  icon-dir:   "file:/data/captcha/icons/"   # 须含 manifest.json（slug / name_zh / confusable）与 <slug>.png
```
默认仅启用 SLIDER，未换私有图库前不建议对外开启 ROTATE / ICON_CLICK。
