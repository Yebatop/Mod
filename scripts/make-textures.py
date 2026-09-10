#!/usr/bin/env python3
"""Рисует картинки мода — всё, что клиент не умеет сложить из прямоугольников.

Знак раньше складывался в игре из прямоугольников — иначе было нечем, — и
выглядел он именно так: блочно. Гладкой диагонали из прямоугольников не
получается, а знак почти весь из диагоналей.

Текстура снимает это ограничение целиком. Заодно в неё запекается свечение,
которое в макете даёт CSS-размытие: у клиента размытия нет, а нарисованное
заранее — есть.

Размеров два, и это не прихоть. Текстуры интерфейса Minecraft рисует без
мип-уровней, поэтому сильное уменьшение бьётся в кашу, а сильное увеличение
мылит. Мелкий вариант обслуживает шапки карточек и панель, крупный — экран
запуска, где знак занимает почти сотню пикселей.

Запуск: python3 scripts/make-textures.py
"""

from __future__ import annotations

import pathlib

from PIL import Image, ImageDraw, ImageFilter

# Знак нарисован в квадрате 88×88 — те же координаты, что в макете, чтобы
# правка в одном месте не расходилась с другим.
ART = 88.0

# Доля холста, которую занимает сам знак. Остальное — поле под свечение:
# оно должно затухнуть до нуля, не упёршись в край, иначе на границе текстуры
# появится заметная ступенька.
ART_SHARE = 208.0 / 256.0

# Сглаживание: рисуем крупно и уменьшаем. У PIL нет сглаживания линий, и это
# единственный способ получить чистую диагональ.
SUPERSAMPLE = 8

# Градиент штриха — сверху слева тёплый белый, снизу справа тёмная бронза.
GRADIENT = ((255, 226, 182), (242, 180, 92), (185, 118, 31))
GRADIENT_MID = 0.52

GLOW = (242, 180, 92)
GLOW_ALPHA = 46

CHART = (70, 211, 217)
FACE = (242, 180, 92, 18)

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/holyhelper/textures/gui"


# ── тень ────────────────────────────────────────────────────────────────
# Тень режется на девять кусков: четыре угла рисуются как есть, четыре края
# и середина растягиваются. Иначе одна картинка на панель любой формы
# растянула бы и размытие в углах, и тень поехала бы вслед за пропорциями.
SHADOW = 128
SHADOW_INSET = 32      # поле под размытие, наружу от панели
SHADOW_RADIUS = 16     # скругление самой панели в координатах картинки
SHADOW_BLUR = 12
SHADOW_ALPHA = 195


def shadow() -> Image.Image:
    """Мягкая тень под панель: скруглённый прямоугольник, размытый наружу."""
    work = SHADOW * SUPERSAMPLE
    inset = SHADOW_INSET * SUPERSAMPLE
    mask = Image.new("L", (work, work), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [inset, inset, work - inset, work - inset],
        radius=SHADOW_RADIUS * SUPERSAMPLE,
        fill=SHADOW_ALPHA,
    )
    mask = mask.filter(ImageFilter.GaussianBlur(radius=SHADOW_BLUR * SUPERSAMPLE))

    canvas = Image.new("RGBA", (work, work), (0, 0, 0, 0))
    canvas.paste(Image.new("RGBA", (work, work), (0, 0, 0, 255)), (0, 0), mask)
    return canvas.resize((SHADOW, SHADOW), Image.LANCZOS)


def gradient_image(size: int) -> Image.Image:
    """Диагональный градиент штриха, из угла в угол."""
    image = Image.new("RGB", (size, size))
    pixels = image.load()
    for y in range(size):
        for x in range(size):
            # Проекция на диагональ: 0 в левом верхнем углу, 1 в правом нижнем.
            t = (x + y) / (2.0 * (size - 1))
            if t <= GRADIENT_MID:
                k = t / GRADIENT_MID
                a, b = GRADIENT[0], GRADIENT[1]
            else:
                k = (t - GRADIENT_MID) / (1.0 - GRADIENT_MID)
                a, b = GRADIENT[1], GRADIENT[2]
            pixels[x, y] = (
                round(a[0] + (b[0] - a[0]) * k),
                round(a[1] + (b[1] - a[1]) * k),
                round(a[2] + (b[2] - a[2]) * k),
            )
    return image


def render(size: int) -> Image.Image:
    work = size * SUPERSAMPLE
    art = size * ART_SHARE * SUPERSAMPLE
    scale = art / ART
    offset = (work - art) / 2.0

    def p(x: float, y: float) -> tuple[float, float]:
        return (offset + x * scale, offset + y * scale)

    def stroke(width: float) -> float:
        return max(1.0, width * scale)

    hexagon = [p(44, 12), p(70, 26), p(70, 54), p(44, 68), p(18, 54), p(18, 26)]

    # ── свечение ────────────────────────────────────────────────────────
    glow = Image.new("L", (work, work), 0)
    ImageDraw.Draw(glow).ellipse(
        [offset * 0.2, offset * 0.2, work - offset * 0.2, work - offset * 0.2],
        fill=GLOW_ALPHA,
    )
    glow = glow.filter(ImageFilter.GaussianBlur(radius=work * 0.032))

    canvas = Image.new("RGBA", (work, work), (0, 0, 0, 0))
    canvas.paste(Image.new("RGBA", (work, work), GLOW + (255,)), (0, 0), glow)

    # ── грань блока ─────────────────────────────────────────────────────
    face = Image.new("RGBA", (work, work), (0, 0, 0, 0))
    ImageDraw.Draw(face).polygon(hexagon, fill=FACE)
    canvas = Image.alpha_composite(canvas, face)

    # ── внутренние рёбра, вполсилы ──────────────────────────────────────
    inner = Image.new("L", (work, work), 0)
    draw = ImageDraw.Draw(inner)
    draw.line([p(18, 26), p(44, 40), p(70, 26)], fill=102, width=round(stroke(2)), joint="curve")
    draw.line([p(44, 40), p(44, 68)], fill=102, width=round(stroke(2)))

    # ── контур блока ────────────────────────────────────────────────────
    outline = Image.new("L", (work, work), 0)
    draw = ImageDraw.Draw(outline)
    draw.line(hexagon + [hexagon[0]], fill=255, width=round(stroke(3)), joint="curve")

    gold = gradient_image(work).convert("RGBA")
    canvas.paste(gold, (0, 0), inner)
    canvas.paste(gold, (0, 0), outline)

    # ── линия графика ───────────────────────────────────────────────────
    line = Image.new("L", (work, work), 0)
    draw = ImageDraw.Draw(line)
    points = [p(27, 52), p(38, 44), p(49, 49), p(62, 32)]
    width = round(stroke(4))
    draw.line(points, fill=255, width=width, joint="curve")
    # Круглые концы: joint="curve" скругляет только стыки, но не концы.
    for x, y in (points[0], points[-1]):
        r = width / 2.0
        draw.ellipse([x - r, y - r, x + r, y + r], fill=255)
    canvas.paste(Image.new("RGBA", (work, work), CHART + (255,)), (0, 0), line)

    return canvas.resize((size, size), Image.LANCZOS)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    pictures = {"mark": render(96), "mark_large": render(256), "shadow": shadow()}
    for name, image in pictures.items():
        path = OUT / f"{name}.png"
        image.save(path)
        # blur — билинейная фильтрация: без неё клиент растянул бы текстуру
        # соседним пикселем, и вся затея потеряла бы смысл.
        # clamp — не тянуть края по кругу: у знака прозрачное поле.
        (OUT / f"{name}.png.mcmeta").write_text(
            '{\n  "texture": {\n    "blur": true,\n    "clamp": true\n  }\n}\n',
            encoding="utf-8",
        )
        print(f"{path.relative_to(OUT.parents[4])} — {image.width}×{image.height}")


if __name__ == "__main__":
    main()
