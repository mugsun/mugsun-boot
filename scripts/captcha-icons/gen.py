#!/usr/bin/env python3
# 验证码图标生成脚本（手写 SVG 路径 → cairosvg 转 PNG），产出 svg/、png/64、png/128、manifest.json、overview.png。
# 用法：pip install cairosvg pillow && python3 gen.py <输出目录>
# 仓库内 mugsun-boot-core/src/main/resources/captcha/icons/ 的 PNG 取自 png/64，manifest.json 原样复制。
import json,os,cairosvg
B='#5D87FF';F='#E8EEFF';T='#14B8A6';P='#8B5CF6'
a=f'fill="none" stroke="{B}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"'
f=f'fill="{F}" stroke="{B}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"'
def t(c):return f'fill="none" stroke="{c}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"'
def tf(c):return f'fill="{c}" stroke="{c}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"'
I={
'sun':('太阳',f'<circle cx="32" cy="32" r="11" {f}/><path d="M32 6v8M32 50v8M6 32h8M50 32h8M13.6 13.6l5.7 5.7M44.7 44.7l5.7 5.7M13.6 50.4l5.7-5.7M44.7 19.3l5.7-5.7" {a}/>'),
'moon':('月亮',f'<path d="M40 8a24 24 0 1 0 16 36A20 20 0 0 1 40 8z" {f}/>'),
'star':('星星',f'<path d="M32 6l7.6 16.2 17.4 2.1-12.9 12 3.4 17.4L32 45l-15.5 8.7 3.4-17.4-12.9-12 17.4-2.1z" {f}/>'),
'cloud':('云',f'<path d="M18 48h28a10 10 0 0 0 1-20 14 14 0 0 0-27-3 11 11 0 0 0-2 23z" {f}/>'),
'umbrella':('雨伞',f'<path d="M6 32a26 26 0 0 1 52 0z" {f}/><path d="M32 32v18a6 6 0 0 1-12 0" {a}/><path d="M32 6v0" {a}/>'),
'house':('房子',f'<path d="M14 30v24h36V30" {f}/><path d="M6 32L32 10l26 22" {a}/><path d="M27 54V40h10v14" {t(T)}/>'),
'tree':('树',f'<path d="M32 6L14 34h36z" {f}/><path d="M32 22L12 50h40z" {f}/><path d="M32 50v8" {a}/>'),
'flower':('花',f'<circle cx="32" cy="14" r="8" {f}/><circle cx="20" cy="26" r="8" {f}/><circle cx="44" cy="26" r="8" {f}/><circle cx="32" cy="24" r="5" {tf(P)}/><path d="M32 32v26M32 48c-6-6-12-4-14-2M32 44c6-6 12-4 14-2" {t(T)}/>'),
'leaf':('叶子',f'<path d="M12 52C10 24 28 10 54 10c0 26-14 44-42 42z" {f}/><path d="M12 52L38 26" {a}/>'),
'fish':('鱼',f'<path d="M8 32c10-14 30-14 40 0-10 14-30 14-40 0z" {f}/><path d="M48 32l10-10v20z" {a}/><circle cx="20" cy="30" r="2" fill="{B}"/>'),
'cat':('猫脸',f'<path d="M12 26V8l12 10h16l12-10v18c0 16-9 28-20 28S12 42 12 26z" {f}/><circle cx="24" cy="32" r="2.5" fill="{B}"/><circle cx="40" cy="32" r="2.5" fill="{B}"/><path d="M29 40l3 3 3-3" {a}/>'),
'bell':('铃铛',f'<path d="M14 46c4-4 4-10 4-18a14 14 0 0 1 28 0c0 8 0 14 4 18z" {f}/><path d="M27 52a5 5 0 0 0 10 0M32 8v6" {a}/>'),
'key':('钥匙',f'<circle cx="18" cy="32" r="10" {f}/><path d="M28 32h28M48 32v8M56 32v8" {a}/>'),
'lock':('锁',f'<rect x="14" y="28" width="36" height="28" rx="5" {f}/><path d="M22 28v-8a10 10 0 0 1 20 0v8M32 38v8" {a}/>'),
'heart':('爱心',f'<path d="M32 54S8 40 8 22a12 12 0 0 1 24-4 12 12 0 0 1 24 4c0 18-24 32-24 32z" {f}/>'),
'smile':('笑脸',f'<circle cx="32" cy="32" r="24" {f}/><circle cx="24" cy="26" r="2.5" fill="{B}"/><circle cx="40" cy="26" r="2.5" fill="{B}"/><path d="M22 38c5 7 15 7 20 0" {a}/>'),
'cup':('杯子',f'<path d="M12 22h32v18a12 12 0 0 1-12 12h-8a12 12 0 0 1-12-12z" {f}/><path d="M44 26h4a7 7 0 0 1 0 14h-4M22 8c-2 4 2 6 0 10M32 8c-2 4 2 6 0 10" {a}/>'),
'book':('书',f'<path d="M32 16c-6-5-16-6-24-4v38c8-2 18-1 24 4 6-5 16-6 24-4V12c-8-2-18-1-24 4z" {f}/><path d="M32 16v38" {a}/>'),
'pencil':('铅笔',f'<path d="M44 8l12 12-32 32H12V40z" {f}/><path d="M36 16l12 12" {a}/>'),
'scissors':('剪刀',f'<circle cx="16" cy="46" r="7" {f}/><circle cx="16" cy="18" r="7" {f}/><path d="M22 22l34 22M22 42L56 20" {a}/>'),
'flag':('旗子',f'<path d="M14 58V8" {a}/><path d="M14 10c10-6 18 6 28 0s12 0 12 0v22s-4-6-12 0-18-6-28 0z" {f}/>'),
'rocket':('火箭',f'<path d="M32 6c10 8 12 20 10 34H22C20 26 22 14 32 6z" {f}/><path d="M22 34l-8 10v6l8-4M42 34l8 10v6l-8-4" {a}/><circle cx="32" cy="22" r="4" {t(T)}/><path d="M28 46l4 10 4-10" {t(P)}/>'),
'plane':('飞机',f'<path d="M32 6c3 0 4 4 4 8v12l20 12v6l-20-6v10l6 5v4l-10-3-10 3v-4l6-5V38L8 44v-6l20-12V14c0-4 1-8 4-8z" {f}/>'),
'car':('汽车',f'<path d="M6 42V32l6-12h40l6 12v10z" {f}/><path d="M16 22l-4 10h40l-4-10" {a}/><circle cx="18" cy="44" r="5" {f}/><circle cx="46" cy="44" r="5" {f}/>'),
'bike':('自行车',f'<circle cx="14" cy="42" r="10" {a}/><circle cx="50" cy="42" r="10" {a}/><path d="M14 42l10-20h18l8 20M24 22l10 20h-20M38 14h8l-4 8" {a}/>'),
'boat':('帆船',f'<path d="M6 44h52l-8 12H14z" {f}/><path d="M32 6v38" {a}/><path d="M32 10l18 28H32z" {f}/><path d="M28 16L14 38h14" {t(T)}/>'),
'camera':('相机',f'<rect x="6" y="18" width="52" height="36" rx="6" {f}/><path d="M22 18l4-8h12l4 8" {a}/><circle cx="32" cy="36" r="10" {a}/>'),
'note':('音符',f'<path d="M24 46V12l26-6v34" {a}/><ellipse cx="17" cy="46" rx="8" ry="6" {f}/><ellipse cx="43" cy="40" rx="8" ry="6" {f}/>'),
'bolt':('闪电',f'<path d="M36 4L12 36h18l-4 24 26-34H34z" {f}/>'),
'drop':('水滴',f'<path d="M32 6C22 22 14 30 14 40a18 18 0 0 0 36 0c0-10-8-18-18-34z" {f}/><path d="M24 40a8 8 0 0 0 8 8" {t(T)}/>'),
'fire':('火焰',f'<path d="M32 58c-12 0-20-8-20-20 0-12 10-18 12-30 8 6 8 14 8 14s4-6 4-10c10 8 16 16 16 26 0 12-8 20-20 20z" {f}/><path d="M32 52c-5 0-8-3-8-8s4-8 8-14c4 6 8 9 8 14s-3 8-8 8z" {t(P)}/>'),
'diamond':('钻石',f'<path d="M6 24L18 10h28l12 14-26 32z" {f}/><path d="M6 24h52M24 10l-4 14 12 32 12-32-4-14" {a}/>'),
'crown':('皇冠',f'<path d="M8 20l12 14 12-22 12 22 12-14-4 32H12z" {f}/><path d="M12 52h40" {a}/>'),
'gift':('礼物',f'<rect x="8" y="24" width="48" height="12" rx="2" {f}/><path d="M12 36v20h40V36M32 24v32" {a}/><path d="M32 24c-4-12-18-14-16-4 1 4 10 4 16 4 6 0 15 0 16-4 2-10-12-8-16 4z" {t(P)}/>'),
'bulb':('灯泡',f'<path d="M24 44c0-6-10-10-10-22a18 18 0 0 1 36 0c0 12-10 16-10 22z" {f}/><path d="M24 50h16M26 57h12" {a}/>'),
'clock':('时钟',f'<circle cx="32" cy="32" r="24" {f}/><path d="M32 18v14l10 6" {a}/>'),
'envelope':('信封',f'<rect x="6" y="14" width="52" height="36" rx="4" {f}/><path d="M8 16l24 20 24-20" {a}/>'),
'pin':('地图针',f'<path d="M32 58S12 36 12 24a20 20 0 0 1 40 0c0 12-20 34-20 34z" {f}/><circle cx="32" cy="24" r="7" {t(T)}/>'),
'mountain':('山',f'<path d="M4 54L22 20l10 16 8-12 20 30z" {f}/><path d="M16 31l6 3 5-5" {a}/>'),
'hammer':('锤子',f'<path d="M30 30L10 50a4 4 0 0 0 6 6l20-20" {a}/><path d="M26 18l14-12 18 18-12 14z" {f}/>'),
'piggy':('存钱罐',f'<path d="M10 34c0-12 10-20 24-20 10 0 16 4 20 10h4v12h-4c-2 4-4 6-6 8v8h-8v-6H26v6h-8v-8c-5-3-8-6-8-10z" {f}/><path d="M28 22h10" {a}/><circle cx="46" cy="28" r="2" fill="{B}"/>'),
'phone':('老式电话',f'<path d="M8 22c0-8 48-8 48 0v6H44v-6H20v6H8z" {f}/><path d="M20 30h24l6 24H14z" {f}/><circle cx="32" cy="42" r="5" {a}/>'),
'glasses':('眼镜',f'<circle cx="16" cy="38" r="10" {f}/><circle cx="48" cy="38" r="10" {f}/><path d="M26 38c4-4 8-4 12 0M6 36L10 20M58 36L54 20" {a}/>'),
'anchor':('船锚',f'<circle cx="32" cy="12" r="6" {f}/><path d="M32 18v38M20 28h24M10 38c2 12 12 18 22 18s20-6 22-18" {a}/>'),
'puzzle_bulb_skip':None,
}
I.pop('puzzle_bulb_skip')
# 易混组（同组图标同一题内最多出现一个）；写入 manifest.json 的 confusable 字段，服务端只从清单读取
GROUPS={
 "round-radiant":["sun","star","flower","gift"],"round-face":["moon","smile","cat"],"key-lock":["key","lock"],
 "flying":["plane","rocket"],"cup-bell":["cup","bell"],"plant-flame":["leaf","tree","drop","fire"],
 "water-craft":["fish","boat","anchor"],"wheeled":["car","bike"],"paper":["note","book","envelope"],
 "teardrop":["pin","drop","bulb"],"gem-crown":["diamond","crown"],"phone-camera":["phone","camera"],
 "tools":["pencil","hammer","scissors"],"peak-roof":["mountain","house"],"clock-glasses":["clock","glasses"],
 "house-phone":["house","phone"],"sun-clock":["sun","clock"]}
import sys
D=(sys.argv[1].rstrip('/')+'/') if len(sys.argv)>1 else './out/';[os.makedirs(D+p,exist_ok=True) for p in('svg','png/64','png/128')];man=[]
for k,(n,s) in I.items():
  svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">{s}</svg>'
  open(D+f'svg/{k}.svg','w').write(svg)
  for z in(64,128):cairosvg.svg2png(bytestring=svg.encode(),write_to=D+f'png/{z}/{k}.png',output_width=z,output_height=z)
  man.append({'slug':k,'name_zh':n,'confusable':[g for g,v in GROUPS.items() if k in v]})
json.dump(man,open(D+'manifest.json','w'),ensure_ascii=False,indent=1)
from PIL import Image,ImageDraw,ImageFont
fn=ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',18) if os.path.exists('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc') else ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc',18)
c=8;r=(len(man)+c-1)//c;W,H=160,180
im=Image.new('RGB',(c*W,r*H),'#F2F4F8');d=ImageDraw.Draw(im)
for i,m in enumerate(man):
  x,y=i%c*W,i//c*H;p=Image.open(D+f'png/128/{m["slug"]}.png');im.paste(p,(x+16,y+10),p)
  d.text((x+W/2,y+158),m['name_zh'],fill='#333',font=fn,anchor='mm')
im.save(D+'overview.png');print(len(man))
