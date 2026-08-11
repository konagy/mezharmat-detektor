# -*- coding: utf-8 -*-
"""
Mezharmat-detektor kabocafelvetelekhez.
=======================================

Mit csinal?
  1. Az elso kepkockan kijelolod a kabocat (egerrel).
  2. A program sajat template-koveto algoritmussal vegig koveti az allatot
     (NEM kell hozza opencv-contrib, sem KCF/CSRT/MOSSE tracker).
  3. A kovetett test korul figyeli, mikor jelenik meg egy kicsi, tomor,
     vilagos csepp, ami tobb kepkockan at egy helyben marad.
  4. A gyanus idopontokat CSV-be irja, es minden esemenyrol
     - kivag egy rovid ellenorzo videot,
     - keszit egy kontakt-lapot (kepkocka-sorozat egy kepen).

Hasznalat (parancssorbol, a mappaban allva):

    py mezharmat.py --video "C:\\mezharmat_detektor\\videos\\teszt.mp4"
    py mezharmat.py --mappa "C:\\mezharmat_detektor\\videos"
    py mezharmat.py --video "...\\hosszu.mp4" --roi 686,402,181,128
    py mezharmat.py --video "...\\hosszu.mp4" --tol 3600 --ig 7200

Kapcsolok:
    --video PATH    egyetlen videofajl
    --mappa PATH    egy mappa osszes .mp4 fajlja
    --kimenet PATH  eredmenymappa (alapertelmezes: <mappa>\\..\\output)
    --roi x,y,w,h   ROI megadasa kezzel (nem ugrik fel ablak)
    --tol / --ig    csak a videó adott masodperc-tartomanyat nezi
    --erzekeny      erzekenyebb beallitas (tobb talalat, tobb teves riasztas)
    --szigoru       szigorubb beallitas (kevesebb teves riasztas)
    --nincs-klip    ne vagjon ki ellenorzo videokat
"""

import argparse
import csv
import os
import sys
from datetime import timedelta

import cv2
import numpy as np

# ---------------------------------------------------------------- parameterek
P = dict(
    SEARCH_MARGIN=40,     # koveto keresesi savja px-ben
    CTX_MARGIN=0.35,      # a kabocadoboz koruli extra sav aranya
    BG_LEN=20,            # hattermodell hossza (kepkocka)
    BRIGHT_DELTA=10.0,    # ennyivel legyen vilagosabb a hatternel a csepp
    MIN_AREA=12,          # csepp legkisebb terulete (px)
    MAX_AREA=400,         # csepp legnagyobb terulete (px)
    MIN_FILL=0.65,        # tomorseg: terulet / befoglalo teglalap
    MOTION_MAX=0.9,       # ennel hevesebb mozgas eseten a kepkocka kimarad
    MAX_STEP=7.0,         # a csepp ennyit mozdulhat kepkockankent (px)
    MIN_PERSIST=7,        # ennyi kepkockan at legyen jelen -> esemeny
    MERGE_GAP_S=1.0,      # ennel kozelebbi esemenyek osszevonasa
    MIN_SCORE=350.0,      # ez alatti pontszamu talalat "gyenge" jelolest kap
)
P_ALAP = dict(P)


def beallit(mod):
    P.update(P_ALAP)
    if mod == "erzekeny":
        P.update(BRIGHT_DELTA=7.0, MIN_PERSIST=5, MIN_FILL=0.55,
                 MOTION_MAX=1.3, MIN_SCORE=200.0)
    elif mod == "szigoru":
        P.update(BRIGHT_DELTA=13.0, MIN_PERSIST=9, MIN_FILL=0.72,
                 MOTION_MAX=0.7, MIN_SCORE=450.0)


def ido(mp):
    td = timedelta(seconds=float(mp))
    t = int(td.total_seconds())
    return "%02d:%02d:%02d.%03d" % (t // 3600, (t % 3600) // 60, t % 60,
                                    int((mp - int(mp)) * 1000))


# ------------------------------------------------------------------- koveto
# A koveto beallitasai. Ket kulon hiba miatt kellett ide ketszer visszanyulni.
#
# 1. hiba (regi kovetoben): amikor az allat elugrott, a hatteren maradt keret
#    is 0.55 koruli egyezest kapott, es a program meg sem probalta a nagy
#    keresest. Megoldas: addig bovitjuk a keresest (helyi - kozepes - teljes
#    kep), amig nincs MEGGYOZO egyezes.
#
# 2. hiba (ezt most javitottuk): ha SEHOL a kepen nincs MEGGYOZO egyezes
#    (pl. az allat elbujt vagy arnyekba ment), a regi kod meg a leggyengebb,
#    MIN=0.38-nal jobb talalatot is elfogadta -- ez oda vezetett, hogy a
#    keret ratapadt egy masik, csak veletlenul hasonlo texturaju mozgo
#    dologra (mas rovar, level), es onnan mar azt kovette, mint egy sima
#    mozgaserzekelo. Megoldas: a keret csak akkor mozdul, ha a talalat
#    legalabb OK (0.60) eros; kulonben inkabb a helyen marad (bizonytalannak
#    jelezzuk), mint hogy rossz helyre ugorjon.
KOV = dict(
    HELYI_MARGO=55,        # elso kor: ekkora savban keres a legutobbi pozicio korul
    KOZEPES_MARGO=220,     # masodik kor
    MEGGYOZO=0.78,         # ennel jobb egyezes: nem kell tovabb keresni
    OK=0.60,               # ez alatt bizonytalan a kovetes -- ES ez alatt a
                           # keret sem mozdul, inkabb a helyen marad
    MIN=0.38,              # mar nem hasznalt elfogadasi kuszobkent, csak jelzesre
    BANK_MAX=3,
    BANK_KOZ=90,
    SKALAK=(0.80, 0.90, 1.11, 1.25),      # helyi keresesnel ezekkel is probal
    TELJES_SKALAK=(0.70, 0.80, 0.90, 1.0, 1.12, 1.28),
    TELJES_SZOGEK=(0, -40, -25, -12, 12, 25, 40),
    MERET_FIX=True,        # a keret merete allando maradjon (csak a helye valtozik)
    KOZEPES_KOZ=4,         # felenges egyezesnel ennyi kepkockankent bovit keresest
    TELJES_KOZ=10,         # elveszes utan ennyi kepkockankent keres az egesz kepen
    TELJES_ZSUGOR=0.5,
)


def _forgat(tpl, szog):
    if szog == 0:
        return tpl
    h, w = tpl.shape[:2]
    M = cv2.getRotationMatrix2D((w / 2.0, h / 2.0), szog, 1.0)
    return cv2.warpAffine(tpl, M, (w, h), borderMode=cv2.BORDER_REFLECT)


class Koveto:
    def __init__(self, gray, bbox, be=None):
        self.be = dict(KOV, **(be or {}))
        x, y, w, h = [int(v) for v in bbox]
        self.x, self.y, self.w, self.h = x, y, w, h
        self.w0, self.h0 = w, h          # eredeti meret
        self.elveszett = 0
        self.n = 0
        self.utolso_pontszam = 1.0
        self.bizonytalan_kepkockak = 0
        self._horgonyoz(gray, bbox)

    # ------------------------------------------------------------------ belso
    def _horgonyoz(self, gray, bbox):
        x, y, w, h = [int(v) for v in bbox]
        tpl = gray[y:y + h, x:x + w].astype(np.float32)
        self.horgony = tpl.copy()
        self.bank = [tpl.copy()]
        self._cache_epit()

    def _cache_epit(self):
        """Elore kiszamolt minta-valtozatok, hogy ne kelljen kepkockankent szamolni."""
        z = self.be["TELJES_ZSUGOR"]
        self.skalazott = []
        for tpl in (self.horgony, self.bank[-1]):
            for sk in self.be["SKALAK"]:
                nw, nh = int(tpl.shape[1] * sk), int(tpl.shape[0] * sk)
                if nw >= 12 and nh >= 12:
                    self.skalazott.append(cv2.resize(tpl, (nw, nh)))
        # nagy kereses: skala ES elfordulas egyutt, kicsinyitve
        self.teljes = []
        for tpl in (self.horgony, self.bank[-1]):
            for sk in self.be["TELJES_SKALAK"]:
                nw, nh = int(tpl.shape[1] * sk * z), int(tpl.shape[0] * sk * z)
                if nw < 10 or nh < 10:
                    continue
                alap = cv2.resize(tpl, (nw, nh))
                for szog in self.be["TELJES_SZOGEK"]:
                    self.teljes.append(_forgat(alap, szog))

    def _illeszt(self, gray32, x0, y0, x1, y1, tpl):
        win = gray32[y0:y1, x0:x1]
        th, tw = tpl.shape[:2]
        if win.shape[0] < th or win.shape[1] < tw:
            return None, -1.0
        r = cv2.matchTemplate(win, tpl, cv2.TM_CCOEFF_NORMED)
        _, mx, _, loc = cv2.minMaxLoc(r)
        return (x0 + loc[0], y0 + loc[1], tw, th), float(mx)

    def _kereses(self, g32, margo, skalazzunk):
        H, W = g32.shape
        x0 = max(0, self.x - margo); y0 = max(0, self.y - margo)
        x1 = min(W, self.x + self.w + margo); y1 = min(H, self.y + self.h + margo)
        legjobb, pont = None, -1.0
        for tpl in [self.horgony] + self.bank:
            b, s = self._illeszt(g32, x0, y0, x1, y1, tpl)
            if s > pont:
                legjobb, pont = b, s
        if skalazzunk:
            for tpl in self.skalazott:
                b, s = self._illeszt(g32, x0, y0, x1, y1, tpl)
                if s > pont + 0.03:      # meretvaltast csak erdemi javulasnal
                    legjobb, pont = b, s
        return legjobb, pont

    def _teljes_kep(self, gray, g32):
        z = self.be["TELJES_ZSUGOR"]
        kis = cv2.resize(gray, None, fx=z, fy=z, interpolation=cv2.INTER_AREA).astype(np.float32)
        H, W = kis.shape
        legjobb, pont = None, -1.0
        for tpl in self.teljes:
            if tpl.shape[0] > H or tpl.shape[1] > W:
                continue
            b, s = self._illeszt(kis, 0, 0, W, H, tpl)
            if s > pont:
                legjobb, pont = b, s
        if legjobb is None:
            return None, -1.0
        bx, by = int(legjobb[0] / z), int(legjobb[1] / z)
        bw, bh = max(12, int(legjobb[2] / z)), max(12, int(legjobb[3] / z))
        # pontositas teljes felbontason a talalt hely korul
        ment = (self.x, self.y, self.w, self.h)
        self.x, self.y, self.w, self.h = bx, by, bw, bh
        b2, s2 = self._kereses(g32, 30, True)
        self.x, self.y, self.w, self.h = ment
        if b2 is not None and s2 >= pont:
            return b2, s2
        return (bx, by, bw, bh), pont

    # ---------------------------------------------------------------- publikus
    def update(self, gray):
        self.n += 1
        g32 = gray.astype(np.float32)
        kell_skala = self.utolso_pontszam < self.be["MEGGYOZO"]

        b, s = self._kereses(g32, self.be["HELYI_MARGO"], kell_skala)
        kuszob = self.be["OK"]          # a szuk, kozeli keresesnel ez is eleg
        # Amig nincs meggyozo egyezes, bovitjuk a keresest. Ha az egyezes csak
        # "felenges" (OK es MEGGYOZO kozott), nem minden kepkockan bovitunk --
        # igy nem lassul le a feldolgozas, de a valodi elvesztest elkapjuk.
        if s < self.be["MEGGYOZO"]:
            bovit = (s < self.be["OK"]) or (self.n % self.be["KOZEPES_KOZ"] == 0)
            if bovit:
                b2, s2 = self._kereses(g32, self.be["KOZEPES_MARGO"], True)
                if s2 > s:
                    b, s = b2, s2
                    kuszob = self.be["MEGGYOZO"]   # tavoli talalat: szigorubb mertek kell
        if s < self.be["MEGGYOZO"] and self.elveszett % self.be["TELJES_KOZ"] == 0:
            b3, s3 = self._teljes_kep(gray, g32)
            if s3 > s:
                b, s = b3, s3
                kuszob = self.be["MEGGYOZO"]       # teljes kepes talalat: szigorubb mertek kell

        self.utolso_pontszam = s
        if s < self.be["OK"]:
            self.bizonytalan_kepkockak += 1

        # Kozeli (szuk savos) korrekciohoz OK is eleg -- ott nem valik el
        # tavoli, csak veletlenul hasonlo dolog. De ha a talalat a kozepes
        # vagy teljes kepes keresesbol jott (tavolabbrol), csak MEGGYOZO
        # egyezesnel fogadjuk el -- kulonben egy masik, csak felig hasonlo
        # mozgo dolog (masik rovar, level) is ratudna venni a keretet.
        if b is not None and s >= kuszob:
            self.x, self.y, self.w, self.h = b
            self.elveszett = 0
            if s >= self.be["MEGGYOZO"] and self.n % self.be["BANK_KOZ"] == 0:
                p = gray[self.y:self.y + self.h, self.x:self.x + self.w].astype(np.float32)
                if p.shape[0] >= 12 and p.shape[1] >= 12:
                    self.bank.append(p)
                    if len(self.bank) > self.be["BANK_MAX"]:
                        self.bank.pop(0)
                    self._cache_epit()
        else:
            self.elveszett += 1

        if self.be["MERET_FIX"] and (self.w != self.w0 or self.h != self.h0):
            # ugyanaz a kozeppont, de az eredeti meret
            cx = self.x + self.w / 2.0
            cy = self.y + self.h / 2.0
            self.w, self.h = self.w0, self.h0
            self.x = max(0, int(cx - self.w / 2.0))
            self.y = max(0, int(cy - self.h / 2.0))
        return (self.x, self.y, self.w, self.h), s

    def ujra_horgonyoz(self, gray, bbox):
        """Ellenorzopont: a felhasznalo ujra kijelolte az allatot."""
        x, y, w, h = [int(v) for v in bbox]
        self.x, self.y, self.w, self.h = x, y, w, h
        self.w0, self.h0 = w, h
        self._horgonyoz(gray, bbox)
        self.elveszett = 0
        self.utolso_pontszam = 1.0


TemplateKoveto = Koveto      # a regi nev is mukodik


def _kornyezet(bbox, shape):
    x, y, w, h = bbox
    mx, my = int(w * P["CTX_MARGIN"]), int(h * P["CTX_MARGIN"])
    X, Y = max(0, x - mx), max(0, y - my)
    X2, Y2 = min(shape[1], x + w + mx), min(shape[0], y + h + my)
    return X, Y, X2 - X, Y2 - Y


# ---------------------------------------------------------------- detektalas
def elemez(video, bbox, tol=None, ig=None, log=print, progress=None, stop=None,
           ellenorzopontok=None):
    """progress(frac 0..1, aktualis_masodperc), stop() -> True eseten megszakit.

    ellenorzopontok: [(masodperc, (x,y,w,h)), ...] -- ezeknel az idopontoknal a
    program ujra a megadott helyre allitja a keretet. Hosszu (tobb oras) videonal
    ez a legbiztosabb megoldas: 10-15 percenkent egy ellenorzopont.
    """
    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        raise IOError("Nem nyithato meg: " + video)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    ossz = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)

    kezdo = int((tol or 0) * fps)
    vege = int(ig * fps) if ig else ossz
    if kezdo:
        cap.set(cv2.CAP_PROP_POS_FRAMES, kezdo)

    ok, frame = cap.read()
    if not ok:
        raise IOError("Ures video vagy rossz kezdopont.")
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    kov = Koveto(gray, bbox)

    # ellenorzopontok kepkocka-szam szerint
    horgonyok = {}
    for mp, bb in (ellenorzopontok or []):
        horgonyok[int(float(mp) * fps)] = tuple(int(v) for v in bb)

    bg, nyomok = [], []
    dobozok = {}
    prev = None
    idx = kezdo
    kihagyott = 0
    elveszett_kepkockak = 0
    egyezesek = {}
    horgony_hasznalt = 0
    cw = ch = None

    while True:
        ok, frame = cap.read()
        if not ok or (vege and idx >= vege):
            break
        idx += 1
        hatar = max(1, min(vege or ossz or idx, ossz or vege or idx))
        if idx % 200 == 0:
            if stop is not None and stop():
                log("   Megszakitva a felhasznalo keresere.")
                break
            if progress is not None:
                elt = max(1, hatar - kezdo)
                progress(min(1.0, (idx - kezdo) / elt), idx / fps)
        if ossz and idx % 3000 == 0:
            log("   ... %s / %s (%.0f%%)" % (ido(idx / fps), ido(hatar / fps),
                                             100.0 * (idx - kezdo) / max(1, hatar - kezdo)))

        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        # ellenorzopont: a felhasznalo altal ujra kijelolt hely
        if horgonyok:
            for hk in list(horgonyok):
                if abs(idx - hk) <= 1:
                    kov.ujra_horgonyoz(gray, horgonyok.pop(hk))
                    horgony_hasznalt += 1
                    bg, prev = [], None       # hatter ujraindul az uj helyen
                    log("   Ellenorzopont: keret ujra beallitva %s-nal." % ido(idx / fps))
                    break

        box, sc = kov.update(gray)
        egyezesek[idx] = sc
        if sc < KOV["OK"]:
            elveszett_kepkockak += 1

        dobozok[idx] = box
        X, Y, W, H = _kornyezet(box, gray.shape)
        if cw is None:
            cw, ch = W, H
        patch = gray[Y:Y + H, X:X + W]
        if patch.shape[0] != ch or patch.shape[1] != cw:
            if patch.size == 0:
                continue
            patch = cv2.resize(patch, (cw, ch))
        patch = cv2.GaussianBlur(patch, (3, 3), 0).astype(np.float32)

        motion = 0.0 if prev is None else float(np.abs(patch - prev).mean())
        prev = patch

        bg.append(patch)
        if len(bg) > P["BG_LEN"]:
            bg.pop(0)
        if len(bg) < P["BG_LEN"]:
            continue
        if motion > P["MOTION_MAX"]:          # heves mozgas / zavaro rovar
            kihagyott += 1
            for t in nyomok:
                t["closed"] = True
            continue

        hatter = np.median(np.stack(bg[:-3]), axis=0)
        d = patch - hatter
        mask = (d > P["BRIGHT_DELTA"]).astype(np.uint8) * 255
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        n, lab, st, ce = cv2.connectedComponentsWithStats(mask, 8)

        jeloltek = []
        for i in range(1, n):
            a = st[i, cv2.CC_STAT_AREA]
            bw, bh = st[i, cv2.CC_STAT_WIDTH], st[i, cv2.CC_STAT_HEIGHT]
            if not (P["MIN_AREA"] <= a <= P["MAX_AREA"]):
                continue
            if a / float(bw * bh) < P["MIN_FILL"]:
                continue
            jeloltek.append((ce[i][0], ce[i][1], a, float(d[lab == i].mean())))

        hasznalt = set()
        for t in nyomok:
            if t["closed"]:
                continue
            best, bd = None, 1e9
            for j, c in enumerate(jeloltek):
                if j in hasznalt:
                    continue
                dist = float(np.hypot(c[0] - t["x"], c[1] - t["y"]))
                if dist < bd:
                    best, bd = j, dist
            if best is not None and bd <= P["MAX_STEP"]:
                c = jeloltek[best]
                hasznalt.add(best)
                t["ut"] = t.get("ut", 0.0) + float(np.hypot(c[0] - t["x"], c[1] - t["y"]))
                t.update(x=c[0], y=c[1], last=idx, n=t["n"] + 1,
                         area=max(t["area"], c[2]), inten=max(t["inten"], c[3]), miss=0)
            else:
                t["miss"] += 1
                if t["miss"] > 2:
                    t["closed"] = True
        for j, c in enumerate(jeloltek):
            if j not in hasznalt:
                nyomok.append(dict(x=c[0], y=c[1], first=idx, last=idx, n=1,
                                   miss=0, closed=False, area=c[2], inten=c[3],
                                   x0=c[0], y0=c[1], ut=0.0))
    cap.release()

    esemenyek = []
    for t in nyomok:
        if t["n"] >= P["MIN_PERSIST"]:
            elmozdulas = float(np.hypot(t["x"] - t.get("x0", t["x"]),
                                        t["y"] - t.get("y0", t["y"])))
            esemenyek.append(dict(start_s=t["first"] / fps, end_s=t["last"] / fps,
                                  elmozdulas=elmozdulas, ut=t.get("ut", 0.0),
                                  frames=t["n"], area=t["area"], intensity=t["inten"],
                                  score=t["n"] * t["inten"],
                                  box=dobozok.get(t["first"], tuple(bbox)),
                                  kov_egyezes=float(np.mean(
                                      [egyezesek.get(k, 1.0)
                                       for k in range(t["first"], t["last"] + 1)]
                                      or [1.0]))))
    esemenyek.sort(key=lambda e: e["start_s"])

    ossze = []
    for e in esemenyek:
        if ossze and e["start_s"] - ossze[-1]["end_s"] < P["MERGE_GAP_S"]:
            m = ossze[-1]
            m["end_s"] = max(m["end_s"], e["end_s"])
            for k in ("frames", "area", "intensity", "score"):
                m[k] = max(m[k], e[k])
            m["kov_egyezes"] = min(m["kov_egyezes"], e["kov_egyezes"])
            if e["score"] > m["score"]:
                m["box"] = e["box"]
        else:
            ossze.append(dict(e))
    for e in ossze:
        e["megbizhatosag"] = "eros" if e["score"] >= P["MIN_SCORE"] else "gyenge"
    fdb = max(1, idx - kezdo)
    return fps, ossze, dict(kihagyott=kihagyott, kovetes_elveszett=elveszett_kepkockak,
                            feldolgozott=idx - kezdo,
                            kovetes_bizonytalan_szazalek=100.0 * elveszett_kepkockak / fdb,
                            horgony_hasznalt=horgony_hasznalt,
                            kovetes_atlag=float(np.mean(list(egyezesek.values()) or [1.0])))


# ------------------------------------------------------------- ellenorzes
def klip_es_kontaktlap(video, e, fps, kimenet, alap, sorszam, keszits_klipet=True):
    t0 = max(0.0, e["start_s"] - 1.5)
    t1 = e["end_s"] + 1.5
    cap = cv2.VideoCapture(video)
    cap.set(cv2.CAP_PROP_POS_FRAMES, int(t0 * fps))

    # nagyitott kivagas a kabocara (kontaktlaphoz es a klip sarkaba)
    bx, by, bw, bh = e.get("box", (0, 0, 0, 0))
    mx, my = int(bw * 0.5), int(bh * 0.5)

    kepek, ir = [], None
    klip_ut = os.path.join(kimenet, "%s_esemeny%02d.mp4" % (alap, sorszam))
    minta = max(1, int((t1 - t0) * fps / 9))
    i = 0
    while True:
        ok, fr = cap.read()
        if not ok or (t0 + i / fps) > t1:
            break
        H, W = fr.shape[:2]
        cx0, cy0 = max(0, bx - mx), max(0, by - my)
        cx1, cy1 = min(W, bx + bw + mx), min(H, by + bh + my)
        if i % minta == 0 and len(kepek) < 9:
            c = fr[cy0:cy1, cx0:cx1].copy()
            if c.size:
                c = cv2.resize(c, (c.shape[1] * 3, c.shape[0] * 3), interpolation=cv2.INTER_CUBIC)
                cv2.putText(c, "%.2fs" % (t0 + i / fps), (8, 28),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 0, 255), 2)
                kepek.append(c)
        if keszits_klipet:
            if ir is None:
                ir = cv2.VideoWriter(klip_ut, cv2.VideoWriter_fourcc(*"mp4v"), fps, (W, H))
            cv2.rectangle(fr, (cx0, cy0), (cx1, cy1), (0, 0, 255), 2)
            cv2.putText(fr, "%s" % ido(t0 + i / fps), (10, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 2)
            ir.write(fr)
        i += 1
    cap.release()
    if ir is not None:
        ir.release()
    if len(kepek) >= 9:
        h0, w0 = kepek[0].shape[:2]
        kepek = [cv2.resize(k, (w0, h0)) for k in kepek]
        sorok = [np.hstack(kepek[k * 3:(k + 1) * 3]) for k in range(3)]
        cv2.imwrite(os.path.join(kimenet, "%s_esemeny%02d.jpg" % (alap, sorszam)),
                    np.vstack(sorok))
    return klip_ut if keszits_klipet else ""


# ------------------------------------------------------- kovetes ellenorzese
def kovetes_video(video, bbox, kimenet, tol=None, ig=None, ellenorzopontok=None,
                  gyorsitas=6, log=print, progress=None, stop=None):
    """Rovid ellenorzo video: rarajzolja a keretet, hogy latszodjon, koveti-e
    a program a kabocat. A 'gyorsitas' azt jelenti, hogy minden hanyadik
    kepkockat mentse ki (6 = hatszoros sebesseg, gyorsan atnezheto)."""
    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        raise IOError("Nem nyithato meg: " + video)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    ossz = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    kezdo = int((tol or 0) * fps)
    vege = int(ig * fps) if ig else ossz
    if kezdo:
        cap.set(cv2.CAP_PROP_POS_FRAMES, kezdo)
    ok, frame = cap.read()
    if not ok:
        raise IOError("Nem olvashato kepkocka a megadott kezdopontnal.")
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    kov = Koveto(gray, bbox)

    horgonyok = {}
    for mp, bb in (ellenorzopontok or []):
        horgonyok[int(float(mp) * fps)] = tuple(int(v) for v in bb)

    alap = os.path.splitext(os.path.basename(video))[0][:40]
    ut = os.path.join(kimenet, "%s_KOVETES.mp4" % alap)
    H, W = frame.shape[:2]
    ir = cv2.VideoWriter(ut, cv2.VideoWriter_fourcc(*"mp4v"), fps, (W, H))
    idx = kezdo
    bizonytalan = 0
    n = 0
    while True:
        ok, frame = cap.read()
        if not ok or (vege and idx >= vege):
            break
        idx += 1
        if idx % 200 == 0:
            if stop is not None and stop():
                log("   Megszakitva a felhasznalo keresere.")
                break
            if progress is not None:
                progress(min(1.0, (idx - kezdo) / max(1, (vege or ossz) - kezdo)), idx / fps)
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        if horgonyok:
            for hk in list(horgonyok):
                if abs(idx - hk) <= 1:
                    kov.ujra_horgonyoz(gray, horgonyok.pop(hk))
                    break
        (bx, by, bw, bh), sc = kov.update(gray)
        n += 1
        jo = sc >= KOV["OK"]
        if not jo:
            bizonytalan += 1
        if (idx - kezdo) % max(1, int(gyorsitas)) != 0:
            continue
        szin = (0, 200, 0) if jo else (0, 0, 255)
        cv2.rectangle(frame, (bx, by), (bx + bw, by + bh), szin, 3)
        cv2.putText(frame, "%s  egyezes=%.2f  %s" % (ido(idx / fps), sc,
                    "OK" if jo else "BIZONYTALAN"),
                    (12, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.9, szin, 2)
        ir.write(frame)
    cap.release()
    ir.release()
    szaz = 100.0 * bizonytalan / max(1, n)
    log("   Kovetes-video: %s" % ut)
    log("   Bizonytalan kepkockak: %.1f%%" % szaz)
    if szaz >= 20.0:
        log("   FIGYELEM! A keret sokszor nem talalta a kabocat -- tegyel be")
        log("            ellenorzopontokat (10-15 percenkent egy ujra-kijeloles).")
    return ut, szaz


# ------------------------------------------------------------------- futtato
CSV_MEZOK = ["video", "kezdet_mp", "veg_mp", "kezdet", "veg", "hossz_kepkocka",
             "csepp_terulet", "fenyesseg", "pontszam", "megbizhatosag",
             "kovetes_egyezes", "ellenorzo_klip"]


def csv_ir(csv_ut, sorok):
    with open(csv_ut, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=CSV_MEZOK, extrasaction="ignore")
        w.writeheader()
        w.writerows(sorok)


def kepkocka(video, idopont=0.0):
    """A videó adott masodperceben levo kepkocka kiolvasasa."""
    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        raise IOError("Nem nyithato meg: " + video)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    if idopont and idopont > 0:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(float(idopont) * fps))
    ok, fr = cap.read()
    if not ok and idopont:          # ha a keresett hely nem elerheto, elso kepkocka
        cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
        ok, fr = cap.read()
    cap.release()
    if not ok:
        raise IOError("Nem olvashato kepkocka (serult vagy nem tamogatott fajl).")
    return fr


def roi_valaszt(video, idopont=0.0):
    """Kijeloles a megadott masodpercnel levo kepkockan (nem az elsőn!)."""
    fr = kepkocka(video, idopont)
    print("Kijeloles a videó %s pontjanal levo kepkockan." % ido(idopont or 0.0))
    print("Jelold ki a KABOCA TESTET egerrel, majd nyomj ENTER-t vagy SPACE-t.")
    cv2.namedWindow("Jelold ki a kabocat", cv2.WINDOW_NORMAL)
    bbox = cv2.selectROI("Jelold ki a kabocat", fr, showCrosshair=True, fromCenter=False)
    cv2.destroyAllWindows()
    if bbox[2] == 0 or bbox[3] == 0:
        raise SystemExit("Nem jelöltél ki érvényes területet.")
    return tuple(int(v) for v in bbox)


def feldolgoz(video, kimenet, roi=None, tol=None, ig=None, klip=True,
              log=print, progress=None, stop=None, ellenorzopontok=None):
    alap = os.path.splitext(os.path.basename(video))[0][:40]
    print = log
    print("\n=== %s" % os.path.basename(video))
    if not os.path.isfile(video):
        mappa = os.path.dirname(video) or "."
        print("   HIBA: nincs ilyen fajl: %s" % video)
        if os.path.isdir(mappa):
            talalt = [f for f in sorted(os.listdir(mappa))
                      if f.lower().endswith((".mp4", ".mov", ".avi", ".mkv"))]
            if talalt:
                print("   A(z) '%s' mappaban ezek a videok vannak:" % mappa)
                for f in talalt:
                    print("      %s" % f)
                print("   Masold be a pontos nevet, vagy futtasd az egesz mappara:")
                print('      py mezharmat.py --mappa "%s"' % mappa)
            else:
                print("   A(z) '%s' mappaban nincs videofajl." % mappa)
        else:
            print("   Ez a mappa sem letezik: %s" % mappa)
        return []
    # A kijeloles az ELEMZETT IDOSAV ELEJEN levo kepkockan tortenik
    bbox = roi or roi_valaszt(video, tol or 0.0)
    print("   ROI: x=%d y=%d w=%d h=%d  (kijeloles ideje: %s)"
          % (bbox[0], bbox[1], bbox[2], bbox[3], ido(tol or 0.0)))
    fps, ev, info = elemez(video, bbox, tol, ig, log=log, progress=progress, stop=stop,
                           ellenorzopontok=ellenorzopontok)
    print("   Feldolgozott kepkocka: %d | kihagyva (heves mozgas): %d" %
          (info["feldolgozott"], info["kihagyott"]))
    biz = info["kovetes_bizonytalan_szazalek"]
    print("   KOVETES: atlagos egyezes %.2f | bizonytalan kepkockak: %.1f%%"
          % (info["kovetes_atlag"], biz))
    if biz >= 20.0:
        print("   FIGYELEM! A keret az idő %.0f%%-aban nem talalta biztosan a kabocat."
              % biz)
        print("            Ellenorizd a kovetes-videoval, es tegyel be")
        print("            ellenorzopontokat (10-15 percenkent egy ujra-kijeloles).")
    elif biz >= 5.0:
        print("   Megjegyzes: %.1f%% bizonytalan kepkocka -- erdemes a kovetest ellenorizni."
              % biz)
    if not ev:
        print("   Nem talaltam mezharmat-gyanus esemenyt.")
    sorok = []
    for k, e in enumerate(sorted(ev, key=lambda x: -x["score"]), 1):
        print("   %d. %s - %s  pontszam=%.0f  (%s)"
              % (k, ido(e["start_s"]), ido(e["end_s"]), e["score"], e["megbizhatosag"]))
        klip_ut = klip_es_kontaktlap(video, e, fps, kimenet, alap, k, klip)
        sorok.append(dict(video=os.path.basename(video),
                          kezdet_mp=round(e["start_s"], 2), veg_mp=round(e["end_s"], 2),
                          kezdet=ido(e["start_s"]), veg=ido(e["end_s"]),
                          hossz_kepkocka=e["frames"], csepp_terulet=int(e["area"]),
                          fenyesseg=round(e["intensity"], 1), pontszam=round(e["score"]),
                          megbizhatosag=e["megbizhatosag"],
                          kovetes_egyezes=round(e.get("kov_egyezes", 1.0), 2),
                          ellenorzo_klip=os.path.basename(klip_ut)))
        sorok[-1]["_klip"] = klip_ut
        sorok[-1]["_kep"] = os.path.join(kimenet, "%s_esemeny%02d.jpg" % (alap, k))
    return sorok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video")
    ap.add_argument("--mappa")
    ap.add_argument("--kimenet")
    ap.add_argument("--roi")
    ap.add_argument("--tol", type=float)
    ap.add_argument("--ig", type=float)
    ap.add_argument("--erzekeny", action="store_true")
    ap.add_argument("--szigoru", action="store_true")
    ap.add_argument("--nincs-klip", dest="nincs_klip", action="store_true")
    ap.add_argument("--ellenorzopont", action="append", default=[],
                    help="Ujra-kijeloles egy idopontban. Tobbszor is megadhato. "
                         "Formaja: masodperc:x,y,w,h  pl. 900:640,400,180,130")
    ap.add_argument("--kovetes-video", dest="kovetes_video", action="store_true",
                    help="Csak ellenorzo videot keszit a keret mozgasarol, nem elemez.")
    ap.add_argument("--gyorsitas", type=int, default=6,
                    help="A kovetes-video gyorsitasa (6 = hatszoros).")
    a = ap.parse_args()

    if a.erzekeny:
        beallit("erzekeny")
    if a.szigoru:
        beallit("szigoru")

    if a.video:
        videok = [a.video]
        alapmappa = os.path.dirname(os.path.abspath(a.video))
    elif a.mappa:
        if not os.path.isdir(a.mappa):
            raise SystemExit("Nincs ilyen mappa: %s" % a.mappa)
        videok = [os.path.join(a.mappa, f) for f in sorted(os.listdir(a.mappa))
                  if f.lower().endswith((".mp4", ".mov", ".avi", ".mkv"))]
        if not videok:
            raise SystemExit("Nincs videofajl ebben a mappaban: %s" % a.mappa)
        print("Talalt videok (%d db):" % len(videok))
        for v in videok:
            print("   %s" % os.path.basename(v))
        alapmappa = os.path.abspath(a.mappa)
    else:
        ap.error("Adj meg --video vagy --mappa kapcsolot.")

    kimenet = a.kimenet or os.path.join(os.path.dirname(alapmappa), "output")
    os.makedirs(kimenet, exist_ok=True)

    roi = None
    if a.roi:
        roi = tuple(int(v) for v in a.roi.split(","))

    # ellenorzopontok beolvasasa:  masodperc:x,y,w,h
    epontok = []
    for s in a.ellenorzopont:
        try:
            mp, koord = s.split(":")
            epontok.append((float(mp), tuple(int(v) for v in koord.split(","))))
        except Exception:
            raise SystemExit("Rossz --ellenorzopont ertek: %s\n"
                             "Helyes forma: 900:640,400,180,130" % s)
    epontok.sort()

    if a.kovetes_video:
        for v in videok:
            bbox = roi or roi_valaszt(v, a.tol or 0.0)
            print("   ROI: %s" % (bbox,))
            kovetes_video(v, bbox, kimenet, a.tol, a.ig, epontok, a.gyorsitas)
        return

    mind = []
    for v in videok:
        try:
            mind += feldolgoz(v, kimenet, roi, a.tol, a.ig, not a.nincs_klip,
                              ellenorzopontok=epontok)
        except Exception as ex:
            print("   HIBA: %s" % ex)

    csv_ut = os.path.join(kimenet, "mezharmat_esemenyek.csv")
    csv_ir(csv_ut, mind)
    print("\nOsszes talalat: %d" % len(mind))
    print("CSV: %s" % csv_ut)
    print("Ellenorzo klipek es kontaktlapok ugyanitt: %s" % kimenet)


if __name__ == "__main__":
    main()
