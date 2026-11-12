#!/usr/bin/env python3
"""
Gera a arte animada do TuneGrab para o README (docs/player-demo.gif).

Um mockup do PLAYER DE VÍDEO do app, com a mesma paleta e o mesmo
comportamento dos controles próprios (v0.22.26): os controles entram e
saem com fade + escala, o ícone de play GIRA ao virar pause, o botão dá
um pop elástico ao toque e a barra de progresso desliza.

O GIF é gerado frame a frame com Pillow — nada de imagem do app, tudo
desenhado com a paleta oficial (app/src/main/res/values/colors.xml).

Uso:  python3 tools/gerar_arte.py
"""

import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---------- paleta (copiada de app/src/main/res/values/colors.xml) ----------
BG = (0x14, 0x12, 0x0D)
SURFACE = (0x21, 0x1E, 0x15)
STROKE = (0x3A, 0x33, 0x20)
PRIMARY = (0xE8, 0xC1, 0x58)
SECONDARY = (0xC9, 0x97, 0x4C)
ON_BG = (0xF1, 0xEE, 0xE5)
ON_SURFACE_VAR = (0xA7, 0x9F, 0x8C)
ICON_BG_START = (0xA0, 0x6E, 0x12)
ICON_BG_END = (0xF5, 0xD1, 0x61)
STATE_DONE = (0x4A, 0xDE, 0x80)

# gradiente do disco dourado do botão de play (v0.22.26)
PLAY_TOP = (0xFF, 0xD9, 0x7A, 255)
PLAY_MID = PRIMARY + (255,)
PLAY_BOTTOM = (0xD9, 0x97, 0x1F, 255)
PLAY_HALO = (0x4D, 0xF5, 0xD1, 0x40)      # #4DF5D161 do app, mais discreto
# botões laterais: PRETO translúcido (o alpha é o que faz o disco existir)
SIDE_TOP = (0, 0, 0, 0x8C)
SIDE_MID = (0, 0, 0, 0x73)
SIDE_BOTTOM = (0, 0, 0, 0x59)

# ---------- geometria (o frame tem 2px por dp, como um celular ~400dp) ----------
W, H = 640, 360
DP = 1.6

FPS = 12
DURACAO = 6.0
N_FRAMES = int(FPS * DURACAO)

FONTE_DIR = "/usr/share/fonts/noto"
F_TEMPO = os.path.join(FONTE_DIR, "NotoSans-Medium.ttf")
F_NUM = os.path.join(FONTE_DIR, "NotoSans-Bold.ttf")


def fonte(caminho, tamanho):
    try:
        return ImageFont.truetype(caminho, tamanho)
    except Exception:
        return ImageFont.load_default()


# ---------- helpers de desenho ----------


def disco_gradiente(diametro, topo, meio, base, halo=None, halo_extra=0):
    """Disco com gradiente vertical (topo -> meio -> base) e halo opcional."""
    d = int(diametro)
    if halo is not None:
        dh = int(d + halo_extra)
        camada = Image.new("RGBA", (dh, dh), (0, 0, 0, 0))
        dd = ImageDraw.Draw(camada)
        dd.ellipse((0, 0, dh - 1, dh - 1), fill=halo)
        camada = camada.filter(ImageFilter.GaussianBlur(d * 0.06))
        camada.alpha_composite(_disco_com_gradiente(d, topo, meio, base), (int(halo_extra / 2), int(halo_extra / 2)))
        return camada
    return _disco_com_gradiente(d, topo, meio, base)


def _disco_com_gradiente(d, topo, meio, base):
    """Gradiente RADIAL recortado num disco (RGBA).

    Radial em vez de linear: a luz vem de cima (como um botão de verdade) e
    a transição é mais suave — o gradiente linear deixava faixas visíveis
    quando o GIF quantiza as cores (banding).
    """
    g = Image.new("RGBA", (d, d), (0, 0, 0, 0))
    px = g.load()
    # centro da luz: um pouco acima e a esquerda do meio
    lx, ly = d * 0.42, d * 0.30
    raio = d * 0.78
    for y in range(d):
        for x in range(d):
            t = min(1.0, math.hypot(x - lx, y - ly) / raio)
            if t < 0.5:
                u = t / 0.5
                cor = tuple(int(topo[i] + (meio[i] - topo[i]) * u) for i in range(4))
            else:
                u = (t - 0.5) / 0.5
                cor = tuple(int(meio[i] + (base[i] - meio[i]) * u) for i in range(4))
            px[x, y] = cor
    mascara = Image.new("L", (d, d), 0)
    ImageDraw.Draw(mascara).ellipse((0, 0, d - 1, d - 1), fill=255)
    g.putalpha(mascara)
    return g


def icone_play(d, cx, cy, tamanho, cor):
    """Triangulo de play, apontando pra direita."""
    h = tamanho * 0.62
    w = h * 0.88
    x0 = cx - w * 0.38
    x1 = x0 + w
    y0 = cy - h / 2
    y1 = cy + h / 2
    d.polygon([(x0, y0), (x0, y1), (x1, cy)], fill=cor)


def icone_pause(d, cx, cy, tamanho, cor):
    """Duas barras verticais."""
    h = tamanho * 0.66
    w = tamanho * 0.20
    gap = tamanho * 0.14
    y0 = cy - h / 2
    y1 = cy + h / 2
    d.rectangle((cx - gap - w, y0, cx - gap, y1), fill=cor)
    d.rectangle((cx + gap, y0, cx + gap + w, y1), fill=cor)


def icone_seek(d, cx, cy, raio, cor, font, sentido):
    """Seta circular (replay/forward) com o '10' no centro."""
    largura = max(2, int(raio * 0.20))
    bbox = (cx - raio, cy - raio, cx + raio, cy + raio)
    # o angulo da PONTA da seta (em graus, 0 = 3h, cresce no sentido horario)
    if sentido == "back":
        ang_ini, ang_fim = 120, 45          # abre no topo-esquerda
        ponta_ang = ang_ini
    else:
        ang_ini, ang_fim = 300, 375         # abre no topo-direita
        ponta_ang = ang_fim
    d.arc(bbox, start=ang_ini, end=ang_fim, fill=cor, width=largura)
    # a seta fica na PONTA do arco
    a = math.radians(ponta_ang)
    px, py = cx + raio * math.cos(a), cy + raio * math.sin(a)
    # tangente ao circulo, para a seta apontar no sentido do giro
    tx, ty = -math.sin(a), math.cos(a)
    if sentido == "back":
        tx, ty = -tx, -ty
    comp = math.hypot(tx, ty)
    tx, ty = tx / comp, ty / comp
    nx, ny = -ty, tx
    p1 = (px - tx * raio * 0.30 + nx * raio * 0.28,
          py - ty * raio * 0.30 + ny * raio * 0.28)
    p2 = (px - tx * raio * 0.30 - nx * raio * 0.28,
          py - ty * raio * 0.30 - ny * raio * 0.28)
    d.polygon([(px, py), p1, p2], fill=cor)
    d.text((cx, cy + 1), "10", font=font, fill=cor, anchor="mm")


def desenhar_cena():
    """Fundo do 'video': cena escura com um halo dourado difuso."""
    cena = Image.new("RGB", (W, H), (0x0A, 0x09, 0x06))
    d = ImageDraw.Draw(cena)
    # gradiente diagonal base
    for i in range(H):
        t = i / H
        cor = (
            int(0x14 + (0x08 - 0x14) * t),
            int(0x12 + (0x07 - 0x12) * t),
            int(0x0D + (0x05 - 0x0D) * t),
        )
        d.line((0, i, W, i), fill=cor)
    # luz de palco dourada (desfocada)
    luz = Image.new("RGB", (W, H), (0, 0, 0))
    dl = ImageDraw.Draw(luz)
    dl.ellipse((W * 0.18, -H * 0.35, W * 0.62, H * 0.62), fill=(0x5A, 0x48, 0x18))
    dl.ellipse((W * 0.60, H * 0.45, W * 1.05, H * 1.30), fill=(0x33, 0x28, 0x0E))
    luz = luz.filter(ImageFilter.GaussianBlur(70))
    cena = Image.blend(cena, luz, 0.55)
    return cena


def scrim():
    """Escurecimento radial (como o bg_video_scrim do app). Calculado UMA
    vez e reaproveitado — fazer isso por frame custaria segundos."""
    global _SCRIM
    if _SCRIM is not None:
        return _SCRIM
    s = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = s.load()
    cx, cy = W / 2, H / 2
    maxd = math.hypot(cx, cy)
    for y in range(H):
        for x in range(0, W, 2):
            dist = math.hypot(x - cx, y - cy) / maxd
            a = int(0x8C * (1 - dist) ** 1.5 + 0x14 * dist)
            for xx in range(x, min(W, x + 2)):
                px[xx, y] = (0, 0, 0, max(0, min(255, a)))
    _SCRIM = s
    return s


_SCRIM = None


def fmt_ms(ms):
    s = int(ms // 1000)
    return f"{s // 60}:{s % 60:02d}"


# ---------- a cena dos controles ----------


def desenhar_controles(base, alpha, escala, girando, tocando, progresso, duracao_ms, pos_ms):
    """Desenha os controles por cima da cena, com o alpha e a escala dados."""
    if alpha <= 0.001:
        return base

    camada = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(camada)

    # scrim atras dos controles
    sc = scrim()
    alpha_sc = sc.getchannel("A").point(lambda v: int(v * alpha))
    sc.putalpha(alpha_sc)
    camada.alpha_composite(sc)

    # ---- botoes do centro ----
    cx = W / 2
    cy = H / 2
    d_play = int(72 * DP)
    d_side = int(52 * DP)
    gap = int(20 * DP)

    # posicoes com a escala de entrada
    pos_play = (cx, cy)
    pos_back = (cx - d_play / 2 - gap - d_side / 2, cy)
    pos_fwd = (cx + d_play / 2 + gap + d_side / 2, cy)

    def desenha_central(x, y, diametro, halo=None, extra=0):
        disco = disco_gradiente(diametro, PLAY_TOP, PLAY_MID, PLAY_BOTTOM, halo, extra)
        return disco, (x - disco.width / 2, y - disco.height / 2)

    # laterais
    for (x, y) in (pos_back, pos_fwd):
        disco = disco_gradiente(d_side, SIDE_TOP, SIDE_MID, SIDE_BOTTOM)
        camada.alpha_composite(disco, (int(x - disco.width / 2), int(y - disco.height / 2)))

    # centro (o halo do app e discreto: so uma borda fina ao redor do disco)
    disco = disco_gradiente(d_play, PLAY_TOP, PLAY_MID, PLAY_BOTTOM, PLAY_HALO, int(5 * DP))
    camada.alpha_composite(disco, (int(pos_play[0] - disco.width / 2), int(pos_play[1] - disco.height / 2)))

    # icones (girando: o play/pause roda junto com o botao)
    f_num = fonte(F_NUM, int(12 * DP))
    icone_seek(d, pos_back[0], pos_back[1], d_side * 0.31, ON_BG + (255,), f_num, "back")
    icone_seek(d, pos_fwd[0], pos_fwd[1], d_side * 0.31, ON_BG + (255,), f_num, "fwd")

    # o play/pause gira: o ICONE mostrado é sempre o do estado FINAL
    # (tocando -> pause, pausado -> play); a rotação é a transição
    icone_rot = Image.new("RGBA", (d_play, d_play), (0, 0, 0, 0))
    dr = ImageDraw.Draw(icone_rot)
    if tocando:
        icone_pause(dr, d_play / 2, d_play / 2, d_play * 0.42, BG + (255,))
    else:
        icone_play(dr, d_play / 2, d_play / 2, d_play * 0.42, BG + (255,))
    if abs(girando) > 0.001:
        icone_rot = icone_rot.rotate(girando, resample=Image.BICUBIC, center=(d_play / 2, d_play / 2))
    camada.alpha_composite(icone_rot, (int(pos_play[0] - d_play / 2), int(pos_play[1] - d_play / 2)))

    # ---- barra de progresso ----
    pad = int(20 * DP)
    y_barra = int(H - 54 * DP)
    x0, x1 = pad, W - pad
    d.rounded_rectangle((x0, y_barra, x1, y_barra + int(3 * DP)), radius=2, fill=(0xFF, 0xFF, 0xFF, 0x59))
    px = x0 + int((x1 - x0) * progresso)
    if px > x0:
        d.rounded_rectangle((x0, y_barra, px, y_barra + int(3 * DP)), radius=2, fill=PRIMARY + (255,))
    # thumb
    r = int(5 * DP)
    d.ellipse((px - r, y_barra + int(1.5 * DP) - r, px + r, y_barra + int(1.5 * DP) + r), fill=PRIMARY + (255,))

    # tempos
    f_tempo = fonte(F_TEMPO, int(12 * DP))
    y_txt = y_barra + int(12 * DP)
    d.text((x0, y_txt), fmt_ms(pos_ms), font=f_tempo, fill=ON_SURFACE_VAR + (255,))
    # o tempo total fica acima do botao de tela cheia (canto inferior direito)
    d.text((x1 - int(34 * DP), y_txt), fmt_ms(duracao_ms), font=f_tempo,
           fill=ON_SURFACE_VAR + (255,), anchor="ra")

    # ---- botao de tela cheia (canto inferior direito) ----
    d_fullscreen(d, W - int(22 * DP), H - int(28 * DP), int(11 * DP), ON_BG)

    # ---- canto superior: fechar + coracao ----
    d.line((int(22 * DP) - 8, int(22 * DP) - 8, int(22 * DP) + 8, int(22 * DP) + 8), fill=ON_BG, width=3)
    d.line((int(22 * DP) - 8, int(22 * DP) + 8, int(22 * DP) + 8, int(22 * DP) - 8), fill=ON_BG, width=3)
    coracao(d, W - int(28 * DP), int(22 * DP), int(10 * DP), ON_BG)

    # fade final da camada
    a = camada.getchannel("A").point(lambda v: int(v * alpha))
    camada.putalpha(a)

    # a escala de entrada: a camada inteira encolhe em torno do centro
    if abs(escala - 1.0) > 0.001:
        camada = camada.resize((int(W * escala), int(H * escala)), Image.LANCZOS)
        camada = camada.crop((0, 0, W, H))
    return Image.alpha_composite(base, camada)


def d_fullscreen(d, cx, cy, r, cor):
    """Quatro cantos de tela cheia."""
    for sx in (-1, 1):
        for sy in (-1, 1):
            x = cx + sx * r
            y = cy + sy * r
            d.line((x, y, x - sx * r * 0.55, y), fill=cor, width=3)
            d.line((x, y, x, y - sy * r * 0.55), fill=cor, width=3)


def coracao(d, cx, cy, r, cor):
    """Coracao simples (do ic_heart do app)."""
    w = int(r * 1.8)
    h = int(r * 1.6)
    x0, y0 = cx - w // 2, cy - h // 2 + int(r * 0.25)
    d.ellipse((x0, y0, x0 + w // 2, y0 + h // 2), fill=cor)
    d.ellipse((x0 + w // 2, y0, x0 + w, y0 + h // 2), fill=cor)
    d.polygon(
        [(x0 + 2, y0 + h // 2 - 1), (x0 + w - 2, y0 + h // 2 - 1), (cx, y0 + h)],
        fill=cor,
    )


# ---------- timeline ----------


def ease_out(t):
    """Decelerate (entrada dos controles)."""
    return 1 - (1 - t) ** 3


def ease_in(t):
    """Accelerate (saida dos controles)."""
    return t * t


def ease_out_back(t):
    """Overshoot (o pop do botao)."""
    c1 = 1.70158
    c3 = c1 + 1
    return 1 + c3 * (t - 1) ** 3 + c1 * (t - 1) ** 2


def estado(t):
    """(alpha, escala, girando, tocando, progresso, pos_ms, pop) no tempo t."""
    dur_ms = 238_000
    pos = 0

    # --- o video esta TOCANDO? ---
    # toca de 0 a 3.10, pausa de 3.10 a 5.60, volta a tocar depois
    tocando = not (3.10 <= t < 5.60)

    # --- barra: anda enquanto toca ---
    if t < 3.10:
        pos = int(dur_ms * (0.18 + 0.28 * (t / 3.10)))
    elif t < 4.50:
        pos = int(dur_ms * 0.46)
    else:
        pos = int(dur_ms * (0.46 + 0.10 * ((t - 4.50) / 1.50)))

    # --- controles: entram / saem ---
    if t < 0.55:
        a = ease_out(t / 0.55)
        esc = 0.88 + 0.12 * a
        alpha = a
    elif t < 4.50:
        alpha, esc = 1.0, 1.0
    elif t < 4.90:
        u = ease_in((t - 4.50) / 0.40)
        alpha = 1 - u
        esc = 1.0 - 0.12 * u
    elif t < 5.60:
        u = ease_out((t - 4.90) / 0.70)
        alpha = u
        esc = 0.88 + 0.12 * u
    else:
        alpha, esc = 1.0, 1.0

    # --- o play/pause GIRA na troca de estado ---
    girando = 0.0
    if 3.10 <= t < 3.42:
        u = (t - 3.10) / 0.32
        girando = -90 * (1 - ease_out(u))
    elif t < 3.10:
        girando = -90.0
    if 5.60 <= t < 5.92:
        u = (t - 5.60) / 0.32
        girando = 90 * (1 - ease_out(u))
    elif t >= 5.92:
        girando = 0.0

    # --- o pop do botao no clique ---
    pop = 1.0
    for inicio in (3.10, 5.60):
        if inicio <= t < inicio + 0.30:
            u = (t - inicio) / 0.30
            pop = 0.82 + 0.18 * ease_out_back(u)

    return alpha, esc, girando, tocando, pos / dur_ms, pos, pop


def principal():
    cena = desenhar_cena().convert("RGBA")
    frames = []
    for i in range(N_FRAMES):
        t = i / FPS
        alpha, esc, girando, tocando, prog, pos_ms, pop = estado(t)
        img = desenhar_controles(cena, alpha, esc, girando, tocando, prog, 238_000, pos_ms)
        frames.append(img.convert("RGB"))
    return frames


def main():
    raiz = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    destino = os.path.join(raiz, "docs", "player-demo.gif")
    os.makedirs(os.path.dirname(destino), exist_ok=True)

    frames = principal()
    # paleta reduzida: o GIF e indexado, e 128 cores ja sao mais que o
    # suficiente para uma cena de 3 cores + dourado (e mantem o arquivo leve)
    frames = [f.convert("P", palette=Image.ADAPTIVE, colors=128) for f in frames]
    frames[0].save(
        destino,
        save_all=True,
        append_images=frames[1:],
        duration=int(1000 / FPS),
        loop=0,
        optimize=True,
        disposal=2,
    )
    tamanho = os.path.getsize(destino) / 1024
    print(f"gerado: {destino} ({len(frames)} frames, {tamanho:.0f} KB)")


if __name__ == "__main__":
    main()
