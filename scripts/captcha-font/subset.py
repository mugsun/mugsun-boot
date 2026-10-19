#!/usr/bin/env python3
"""重新生成验证码字体子集：Noto Sans CJK SC Bold（SIL OFL 1.1）→ 只含词库与干扰字池用到的字，转 TrueType 轮廓。

用法：python3 subset.py <NotoSansCJK-Bold.ttc 或 NotoSansCJKsc-Bold.otf> <out.ttf> <words.txt> [confusable-chars.txt ...]
依赖：fonttools。覆盖所有给出的文本文件里的字（# 开头的行为注释，空白/TAB 忽略）：词库 + 干扰字映射 = 全部可能出现的字。
"""
import sys
from fontTools.ttLib import TTFont, TTCollection, newTable
from fontTools import subset
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.ttGlyphPen import TTGlyphPen


def load_sc_bold(path):
    if path.lower().endswith(".ttc"):
        for f in TTCollection(path).fonts:
            names = {n.nameID: n.toUnicode() for n in f["name"].names if n.platformID == 3}
            if names.get(4, "").replace(" ", "") in ("NotoSansCJKscBold", "NotoSansCJKSCBold"):
                return f
        raise SystemExit("ttc 里没有 Noto Sans CJK SC Bold")
    return TTFont(path)


def chars_of(words_path):
    out = set()
    for line in open(words_path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            out.update(ch for ch in line if not ch.isspace())
    return out


def cff_to_glyf(font):
    order = font.getGlyphOrder()
    gs = font.getGlyphSet()
    glyf = newTable("glyf")
    glyf.glyphOrder = order
    glyf.glyphs = {}
    for name in order:
        pen = TTGlyphPen(gs)
        gs[name].draw(Cu2QuPen(pen, max_err=1.0, reverse_direction=True))
        glyf.glyphs[name] = pen.glyph()
    font["glyf"] = glyf
    font["loca"] = newTable("loca")
    del font["CFF "]
    if "VORG" in font:
        del font["VORG"]
    font["maxp"].tableVersion = 0x00010000
    for attr in ("maxZones", "maxTwilightPoints", "maxStorage", "maxFunctionDefs", "maxInstructionDefs",
                 "maxStackElements", "maxSizeOfInstructions", "maxComponentElements"):
        setattr(font["maxp"], attr, 0)
    font["maxp"].maxZones = 1
    font["head"].indexToLocFormat = 0
    font["head"].glyphDataFormat = 0
    font["post"].formatType = 2.0
    font["post"].extraNames = []
    font["post"].mapping = {}
    font.sfntVersion = "\x00\x01\x00\x00"


def main(src, out, *texts):
    chars = set()
    for t in texts:
        chars |= chars_of(t)
    font = load_sc_bold(src)
    opts = subset.Options()
    opts.layout_features = []
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.hinting = False
    opts.desubroutinize = True
    s = subset.Subsetter(opts)
    s.populate(unicodes=[ord(c) for c in chars])
    s.subset(font)
    cff_to_glyf(font)
    font.save(out)
    cmap = TTFont(out).getBestCmap()
    missing = [c for c in chars if ord(c) not in cmap]
    if missing:
        raise SystemExit("字体缺字：" + "".join(missing))
    print(f"{len(chars)} chars -> {out}")


if __name__ == "__main__":
    main(*sys.argv[1:])
