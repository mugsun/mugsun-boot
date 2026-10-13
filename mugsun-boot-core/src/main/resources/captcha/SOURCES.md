# 验证码素材来源

| 目录 | 内容 | 来源 / 许可 |
|---|---|---|
| bg/ | 12 张浅色插画底图（600×300） | 体验设计官用 AI 生图工具生成并后期处理（缩放、裁切），无第三方素材。见下「生成方式」。**能否随仓库发布及采用何种许可待用户确认** |
| rotate/ | 16 张旋转题圆图原图（240×240，服务端裁圆） | 体验设计官用 AI 生图工具生成并后期处理（缩放、裁切），无第三方素材。见下「生成方式」。**能否随仓库发布及采用何种许可待用户确认** |
| icons/ | 44 个线性图标（64×64 PNG）+ manifest.json | gen.py 生成的自绘 SVG（转 PNG），脚本在仓库 `scripts/captcha-icons/gen.py`，可完整重现 PNG 与 manifest.json，随项目许可发布。manifest.json 的 `confusable` 为易混组名，同组图标不同场出现 |
| font/NotoSansSC-Bold-subset.ttf | 思源黑体（Noto Sans CJK SC Bold）常用字子集，转为 TrueType 轮廓 | © Google / Adobe，SIL Open Font License 1.1，见 font/OFL.txt；子集为修改版，保留原字体名的保留名限制按 OFL 处理 |

不使用 tianai-captcha 自带素材。

## 生成方式（可追溯性）
- bg/、rotate/：AI 生图工具直接输出的**位图**，没有 SVG 或分层源文件，也没有可重跑的生成脚本；后期处理仅为缩放、裁切（rotate/ 另裁成正方形，服务端运行时再裁圆）。未使用任何第三方照片、图库或他人作品。
- icons/：`scripts/captcha-icons/gen.py` 内手写 SVG 路径，cairosvg 转 64×64 PNG；易混分组 `GROUPS` 同在脚本中，写入 manifest.json 的 `confusable`（服务端只从清单读取）。
- 字体：见上表 OFL 说明。

## 生产环境：建议使用私有图库
本目录随开源仓库发布，图片数量有限（旋转图 16 张、图标 44 个），攻击者可据此预建答案表，ROTATE / ICON_CLICK 的强度会明显下降。生产部署请换成自己的私有素材目录（`file:` 或 `classpath:` 均可），并定期更换：

```yaml
mugsun.captcha:
  background: { dir: "file:/data/captcha/bg/" }
  rotate:     { dir: "file:/data/captcha/rotate/" }
  icon-dir:   "file:/data/captcha/icons/"   # 须含 manifest.json（slug / name_zh / confusable）与 <slug>.png
```
默认仅启用 SLIDER，未换私有图库前不建议对外开启 ROTATE / ICON_CLICK。
