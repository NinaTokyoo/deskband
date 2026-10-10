# -*- coding: utf-8 -*-
"""生成 DeskBand 的启动图标前景（矢量 XML）。

方向由用户选定："小生物扒在一条桌边上（突出桌宠的概念）"，
配色沿用 App 默认主题：深蓝底 #12202B + 青色 #5FD3F3。

⚠️ 只用三次贝塞尔（C），**不要用圆弧（A）**：
   第一版用 `A` 画头，在设备上渲染成了两片小"翅膀"而不是半圆 ——
   圆弧在"半径正好等于弦长一半"（r == chord/2）时是退化情形，
   圆心虽有唯一解但浮点上容易落到另一侧，不同渲染器取法不同。
   circle() 里的 0.5523 是四分之一圆的经典逼近，误差约 0.03%，
   肉眼不可见，而且渲染结果确定。
"""
import io

OUT = "app/src/main/res/drawable/ic_launcher_foreground.xml"

BG = "#12202B"
CYAN = "#5FD3F3"
K = 0.5522847498


def f(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return s if s else "0"


def circle(cx, cy, r):
    """整圆：四段三次贝塞尔"""
    k = r * K
    d = ("M{cx0},{cy} "
         "C{cx0},{cy1} {cx1},{cy0} {cx},{cy0} "
         "C{cx2},{cy0} {cx3},{cy1} {cx3},{cy} "
         "C{cx3},{cy2} {cx2},{cy3} {cx},{cy3} "
         "C{cx1},{cy3} {cx0},{cy2} {cx0},{cy} Z")
    return d.format(
        cx=f(cx), cy=f(cy), cx0=f(cx - r), cx1=f(cx - k),
        cx2=f(cx + k), cx3=f(cx + r), cy0=f(cy - r), cy1=f(cy - k),
        cy2=f(cy + k), cy3=f(cy + r),
    )


def rounded_bar(l, t, r, b, rad):
    """圆角横条"""
    k = rad * K
    d = ("M{l1},{t} H{r1} "
         "C{r2},{t} {r},{t1} {r},{t2} V{b1} "
         "C{r},{b2} {r2},{b} {r1},{b} H{l1} "
         "C{l2},{b} {l},{b2} {l},{b1} V{t2} "
         "C{l},{t1} {l2},{t} {l1},{t} Z")
    return d.format(
        l=f(l), r=f(r), t=f(t), b=f(b),
        l1=f(l + rad), l2=f(l + rad - k), r1=f(r - rad), r2=f(r - rad + k),
        t1=f(t + rad - k), t2=f(t + rad), b1=f(b - rad), b2=f(b - rad + k),
    )


def crescent(cx, cy, rx, ry):
    """下弯的实心月牙（腮红）。

    ⚠️ 必须是**闭合实心**形状：开放弧线（"M.. a.. 0 0,0 .." 不闭合）
       在 Android 上填不出任何东西，等于白画。
    """
    kx, ky = rx * K, ry * K
    d = ("M{cx0},{cy} "
         "C{cx0},{cyb} {cx1},{cya} {cx},{cya} "
         "C{cx2},{cya} {cx3},{cyb} {cx3},{cy} "
         "C{cxi2},{cyi} {cxi1},{cyi} {cx0},{cy} Z")
    return d.format(
        cx=f(cx), cy=f(cy), cx0=f(cx - rx), cx1=f(cx - kx),
        cx2=f(cx + kx), cx3=f(cx + rx),
        cya=f(cy + ry), cyb=f(cy + ky),
        cxi1=f(cx - rx * 0.52), cxi2=f(cx + rx * 0.52), cyi=f(cy + ry * 0.75),
    )


def tuft(x, top, bot):
    """头顶呆毛：两个尖角收成一个水滴状小尖"""
    d = ("M{x},{top} C{x1},{t1} {x2},{t2} {x3},{bot} "
         "C{x1},{bot} {x4},{bot} {x5},{bot} Z")
    return d.format(
        x=f(x), top=f(top), bot=f(bot),
        x1=f(x - 1.2), x2=f(x - 2.4), x3=f(x - 3.4),
        x4=f(x + 1.2), x5=f(x + 3.4),
        t1=f(top + 1.6), t2=f(top + 3.4),
    )


HEAD_CX, HEAD_CY, HEAD_R = 54.0, 48.0, 15.5
BAR_L, BAR_T, BAR_R, BAR_B = 20.0, 63.0, 88.0, 72.0
TUFT_X, TUFT_TOP, TUFT_BOT = 54.0, 29.5, 35.5
EYE_DX, EYE_Y, EYE_R = 5.6, 50.2, 2.4
BLUSH_DX, BLUSH_Y, BLUSH_RX, BLUSH_RY = 10.4, 54.6, 2.9, 2.1


def main():
    paths = [
        ("桌边横条：故意比头宽，视觉上才是\"边缘\"而不是\"底座\"",
         CYAN, rounded_bar(BAR_L, BAR_T, BAR_R, BAR_B, 3.6)),
        ("头顶呆毛：往上戳出头的轮廓，强调\"扒着\"的动作",
         CYAN, tuft(TUFT_X, TUFT_TOP, TUFT_BOT)),
        ("头：正圆", CYAN, circle(HEAD_CX, HEAD_CY, HEAD_R)),
        ("左眼（挖空：填底色）", BG, circle(HEAD_CX - EYE_DX, EYE_Y, EYE_R)),
        ("右眼（挖空：填底色）", BG, circle(HEAD_CX + EYE_DX, EYE_Y, EYE_R)),
        ("左腮红（实心月牙）",
         BG, crescent(HEAD_CX - BLUSH_DX, BLUSH_Y, BLUSH_RX, BLUSH_RY)),
        ("右腮红（实心月牙）",
         BG, crescent(HEAD_CX + BLUSH_DX, BLUSH_Y, BLUSH_RX, BLUSH_RY)),
    ]

    body = []
    for c, fill, d in paths:
        body.append('    <!-- %s -->\n    <path\n        android:fillColor="%s"\n'
                    '        android:pathData="%s" />\n' % (c, fill, d))

    xml = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  启动图标：前景 —— 一只小生物扒在桌边上。

  方向由用户选定："小生物扒在一条桌边上（突出桌宠的概念）"，
  配色沿用 App 默认主题：深蓝底 #12202B + 青色 #5FD3F3。

  ⚠️ 四条硬约束，改图时不要破坏：
    1. **只用三次贝塞尔（C），不要用圆弧（A）**。第一版用 `A` 画头，
       在设备上渲染成了两片小"翅膀"而不是半圆 —— 圆弧在"半径正好等于
       弦长一半"（r == chord/2）时是退化情形，圆心虽有唯一解但浮点上容易
       落到另一侧，不同渲染器取法不同。circle() 里的 0.5523 系数是四分之一
       圆的经典逼近，误差约万分之三、肉眼不可见，且渲染结果确定。
    2. adaptive icon 的**安全区**是中心 66x66（即 [21,87]），圆形/圆角矩形
       遮罩最多只露出这块，所以内容全部收在 [20,88] 以内；最外圈 18 是危险区。
    3. 各厂商遮罩差异很大（圆、方、水滴、齿轮…），图形必须**居中且对称**。
    4. monochrome 复用同一份前景：系统只取 alpha 重新上色，所以"挖空"效果
       （眼睛、腮红用底色画）在单色模式下依然成立。
       ⚠️ 挖空必须画**闭合的实心形状**：开放弧线填不出东西（踩过）。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

__PATHS__</vector>
'''
    # ⚠️ 用 replace 而不是 % 格式化：模板里有中文标点（如全角逗号），
    #    紧跟 % 时会被当成格式符报 "unsupported format character"。
    xml = xml.replace("__PATHS__", "\n".join(body))

    io.open(OUT, "w", encoding="utf-8", newline="").write(xml)
    print("已写 %s" % OUT)


if __name__ == "__main__":
    main()
