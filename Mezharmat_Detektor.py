# -*- coding: utf-8 -*-
"""
Mézharmat Detektor – asztali alkalmazás
=======================================

Grafikus felület a mezharmat.py detektorhoz. Csak Tkinter kell hozzá,
ami a Windows-os Pythonnal együtt telepszik.

Indítás:
    py Mezharmat_Detektor.py
vagy dupla klikk a fájlra (ha a .py a Pythonhoz van társítva).

A mezharmat.py fájlnak ugyanabban a mappában kell lennie.
"""

import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

try:
    import cv2
except ImportError:
    root = tk.Tk(); root.withdraw()
    messagebox.showerror("Hiányzó csomag",
                         "Az OpenCV nincs telepítve.\n\n"
                         "Nyiss egy parancssort és futtasd:\n\n"
                         "py -m pip install opencv-python numpy")
    sys.exit(1)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import mezharmat as mh
except ImportError:
    root = tk.Tk(); root.withdraw()
    messagebox.showerror("Hiányzó fájl",
                         "A mezharmat.py nem található.\n\n"
                         "Tedd ugyanabba a mappába, mint ezt az alkalmazást.")
    sys.exit(1)

VIDEO_TIPUSOK = [("Videófájlok", "*.mp4 *.mov *.avi *.mkv"), ("Minden fájl", "*.*")]


def megnyit(ut):
    """Fájl megnyitása a rendszer alapértelmezett programjával."""
    if not ut or not os.path.exists(ut):
        messagebox.showinfo("Nincs fájl", "Ez a fájl nem található:\n%s" % ut)
        return
    try:
        if sys.platform.startswith("win"):
            os.startfile(ut)          # noqa: S606
        elif sys.platform == "darwin":
            subprocess.Popen(["open", ut])
        else:
            subprocess.Popen(["xdg-open", ut])
    except Exception as ex:
        messagebox.showerror("Hiba", str(ex))


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Mézharmat Detektor")
        self.geometry("1040x940")
        self.minsize(900, 640)

        self.uzenetek = queue.Queue()
        self.leallitas = threading.Event()
        self.szal = None
        self.roi = None
        self.epontok = []            # [(masodperc, (x,y,w,h)), ...]
        self.talalatok = []
        self.kimenet_mappa = ""

        self._epit()
        self.after(120, self._uzenet_pumpa)
        self.protocol("WM_DELETE_WINDOW", self._kilepes)

    # ---------------------------------------------------------------- felület
    def _epit(self):
        st = ttk.Style(self)
        try:
            st.theme_use("vista" if sys.platform.startswith("win") else "clam")
        except tk.TclError:
            pass
        st.configure("Cim.TLabel", font=("Segoe UI", 11, "bold"))
        st.configure("Ind.TButton", font=("Segoe UI", 10, "bold"))

        kul = ttk.Frame(self, padding=12)
        kul.pack(fill="both", expand=True)

        # --- 1. bemenet
        b1 = ttk.LabelFrame(kul, text=" 1. Melyik videót vizsgáljuk? ", padding=10)
        b1.pack(fill="x")
        self.mod = tk.StringVar(value="video")
        ttk.Radiobutton(b1, text="Egy videófájl", variable=self.mod, value="video",
                        command=self._mod_valt).grid(row=0, column=0, sticky="w")
        ttk.Radiobutton(b1, text="Egy egész mappa", variable=self.mod, value="mappa",
                        command=self._mod_valt).grid(row=0, column=1, sticky="w", padx=(16, 0))

        self.utvonal = tk.StringVar()
        ttk.Entry(b1, textvariable=self.utvonal).grid(row=1, column=0, columnspan=3,
                                                     sticky="ew", pady=(8, 0))
        self.tallozo_btn = ttk.Button(b1, text="Tallózás", width=14, command=self._tallozas)
        self.tallozo_btn.grid(row=1, column=3, padx=(8, 0), pady=(8, 0))
        b1.columnconfigure(0, weight=1)
        b1.columnconfigure(1, weight=0)
        b1.columnconfigure(2, weight=1)

        # --- 2. kabóca kijelölése
        b2 = ttk.LabelFrame(kul, text=" 2. Hol van a kabóca? ", padding=10)
        b2.pack(fill="x", pady=(10, 0))
        ttk.Label(b2, text="Jelöld ki a kabóca testét. A kijelölés annál a képkockánál\n"
                           "történik, ahol az elemzés kezdődik (lásd 3. pont: Csak ettől).",
                  justify="left").grid(row=0, column=0, sticky="w")
        ttk.Button(b2, text="Kabóca kijelölése", width=20, command=self._roi_kijelol,
                   style="Ind.TButton").grid(row=0, column=1, padx=(16, 0))
        self.roi_cimke = ttk.Label(b2, text="Még nincs kijelölve (mappás módban\n"
                                           "videónként fel fog ugrani az ablak).",
                                   foreground="#8a6d00", justify="left")
        self.roi_cimke.grid(row=0, column=2, padx=(16, 0), sticky="w")
        ttk.Button(b2, text="Törlés", width=10,
                   command=self._roi_torles).grid(row=0, column=3, padx=(8, 0))
        b2.columnconfigure(2, weight=1)

        # --- 2b. ellenőrzőpontok (hosszú videóhoz)
        b2b = ttk.LabelFrame(kul, text=" 2b. Ellenőrzőpontok - hosszú videóhoz ajánlott ",
                             padding=10)
        b2b.pack(fill="x", pady=(10, 0))
        ttk.Label(b2b, text="Több órás videónál a követés elcsúszhat. Add meg 10-15\n"
                            "percenként, hol van a kabóca: a program ott visszaállítja\n"
                            "a keretet arra a helyre.",
                  justify="left").grid(row=0, column=0, rowspan=3, sticky="w")
        ttk.Button(b2b, text="Ellenőrzőpont hozzáadása", width=26,
                   command=self._epont_hozzaad).grid(row=0, column=1, padx=(16, 0), sticky="ew")
        ttk.Button(b2b, text="Kijelölt törlése", width=26,
                   command=self._epont_torol).grid(row=1, column=1, padx=(16, 0), pady=(4, 0),
                                                   sticky="ew")
        ttk.Button(b2b, text="Összes törlése", width=26,
                   command=self._epont_mind_torol).grid(row=2, column=1, padx=(16, 0),
                                                        pady=(4, 0), sticky="ew")
        self.epont_lista = tk.Listbox(b2b, height=3, font=("Consolas", 9))
        self.epont_lista.grid(row=0, column=2, rowspan=3, padx=(16, 0), sticky="nsew")
        b2b.columnconfigure(2, weight=1)

        # --- 3. beállítások
        b3 = ttk.LabelFrame(kul, text=" 3. Beállítások ", padding=10)
        b3.pack(fill="x", pady=(10, 0))
        ttk.Label(b3, text="Érzékenység:").grid(row=0, column=0, sticky="w")
        self.erz = tk.StringVar(value="alap")
        for i, (c, v) in enumerate([("Szigorú", "szigoru"),
                                    ("Alap (ajánlott)", "alap"),
                                    ("Érzékeny", "erzekeny")]):
            ttk.Radiobutton(b3, text=c, variable=self.erz,
                            value=v).grid(row=0, column=1 + i, sticky="w", padx=(12, 0))
        ttk.Label(b3, text="(szigorú = kevesebb téves riasztás, érzékeny = több találat)",
                  foreground="#555").grid(row=0, column=4, sticky="w", padx=(10, 0))

        ttk.Label(b3, text="Csak ettől (mp):").grid(row=1, column=0, sticky="w", pady=(10, 0))
        self.tol = tk.StringVar()
        ttk.Entry(b3, textvariable=self.tol, width=10).grid(row=1, column=1, sticky="w", pady=(10, 0))
        ttk.Label(b3, text="eddig (mp):").grid(row=1, column=2, sticky="e", pady=(10, 0))
        self.ig = tk.StringVar()
        ttk.Entry(b3, textvariable=self.ig, width=10).grid(row=1, column=3, sticky="w", pady=(10, 0))
        ttk.Label(b3, text="(hosszú videót érdemes szakaszonként futtatni)",
                  foreground="#555").grid(row=1, column=4, sticky="w", padx=(10, 0), pady=(10, 0))

        self.klip = tk.BooleanVar(value=True)
        ttk.Checkbutton(b3, text="Ellenőrző klipek és képek készítése (ajánlott)",
                        variable=self.klip).grid(row=2, column=0, columnspan=4,
                                                 sticky="w", pady=(10, 0))

        ttk.Label(b3, text="Eredmények mappája:").grid(row=3, column=0, sticky="w", pady=(10, 0))
        self.kimenet = tk.StringVar()
        ttk.Entry(b3, textvariable=self.kimenet).grid(row=3, column=1, columnspan=3,
                                                     sticky="ew", pady=(10, 0))
        ttk.Button(b3, text="...", width=4,
                   command=self._kimenet_tallozas).grid(row=3, column=4, sticky="w",
                                                        padx=(8, 0), pady=(10, 0))
        b3.columnconfigure(4, weight=1)

        # --- 4. indítás
        b4 = ttk.Frame(kul)
        b4.pack(fill="x", pady=(12, 0))
        self.indit_btn = ttk.Button(b4, text="Keresés indítása", width=20,
                                    style="Ind.TButton", command=self._indit)
        self.indit_btn.pack(side="left")
        self.kovetes_btn = ttk.Button(b4, text="Követés ellenőrzése", width=22,
                                     command=self._kovetes_ellenoriz)
        self.kovetes_btn.pack(side="left", padx=(8, 0))
        self.stop_btn = ttk.Button(b4, text="Leállítás", width=14,
                                   command=self._stop, state="disabled")
        self.stop_btn.pack(side="left", padx=(8, 0))
        self.folyamat = ttk.Progressbar(b4, mode="determinate", maximum=1000.0)
        self.folyamat.pack(side="left", fill="x", expand=True, padx=(16, 8))
        self.allapot = ttk.Label(b4, text="Készenlétben.", width=26, anchor="e")
        self.allapot.pack(side="right")

        # --- 5. eredmények
        b5 = ttk.LabelFrame(kul, text=" 4. Találatok (kattints duplán a megnyitáshoz) ",
                            padding=8)
        b5.pack(fill="both", expand=False, pady=(12, 0))
        oszlopok = ("kezdet", "veg", "pontszam", "megbizhatosag", "terulet", "video")
        cimek = {"kezdet": "Kezdet", "veg": "Vég", "pontszam": "Pontszám",
                 "megbizhatosag": "Megbízhatóság", "terulet": "Csepp (px)", "video": "Videó"}
        szel = {"kezdet": 105, "veg": 105, "pontszam": 85, "megbizhatosag": 125,
                "terulet": 90, "video": 300}
        self.tabla = ttk.Treeview(b5, columns=oszlopok, show="headings", height=6)
        for o in oszlopok:
            self.tabla.heading(o, text=cimek[o])
            self.tabla.column(o, width=szel[o], anchor="center" if o != "video" else "w")
        self.tabla.tag_configure("eros", background="#e8f6e8")
        self.tabla.tag_configure("gyenge", background="#fdf3e0")
        sb = ttk.Scrollbar(b5, orient="vertical", command=self.tabla.yview)
        self.tabla.configure(yscrollcommand=sb.set)
        self.tabla.pack(side="left", fill="both", expand=True)
        sb.pack(side="left", fill="y")
        self.tabla.bind("<Double-1>", self._sor_megnyit)

        gomb = ttk.Frame(b5)
        gomb.pack(side="left", fill="y", padx=(8, 0))
        ttk.Button(gomb, text="Kép megnyitása", width=18,
                   command=lambda: self._megnyit_kijelolt("_kep")).pack(fill="x")
        ttk.Button(gomb, text="Klip megnyitása", width=18,
                   command=lambda: self._megnyit_kijelolt("_klip")).pack(fill="x", pady=(6, 0))
        ttk.Button(gomb, text="Mappa megnyitása", width=18,
                   command=self._mappa_megnyit).pack(fill="x", pady=(6, 0))
        ttk.Button(gomb, text="CSV megnyitása", width=18,
                   command=self._csv_megnyit).pack(fill="x", pady=(6, 0))

        # --- napló
        b6 = ttk.LabelFrame(kul, text=" Napló ", padding=6)
        b6.pack(fill="both", expand=True, pady=(10, 0))
        self.naplo = tk.Text(b6, height=6, wrap="none", font=("Consolas", 9),
                            background="#1e1e1e", foreground="#d4d4d4")
        nsb = ttk.Scrollbar(b6, orient="vertical", command=self.naplo.yview)
        self.naplo.configure(yscrollcommand=nsb.set, state="disabled")
        self.naplo.pack(side="left", fill="both", expand=True)
        nsb.pack(side="left", fill="y")

        self._mod_valt()

    # --------------------------------------------------------------- események
    def _mod_valt(self):
        self.utvonal.set("")
        self.roi_torles_csendes()

    def _tallozas(self):
        if self.mod.get() == "video":
            p = filedialog.askopenfilename(title="Válaszd ki a videót", filetypes=VIDEO_TIPUSOK)
        else:
            p = filedialog.askdirectory(title="Válaszd ki a mappát")
        if p:
            self.utvonal.set(p)
            alap = p if os.path.isdir(p) else os.path.dirname(p)
            self.kimenet.set(os.path.join(os.path.dirname(alap) or alap, "output"))

    def _kimenet_tallozas(self):
        p = filedialog.askdirectory(title="Eredmények mappája")
        if p:
            self.kimenet.set(p)

    def _kezdo_masodperc(self):
        """A 3. pontban megadott "Csak ettől (mp)" ertek, vagy 0."""
        s = self.tol.get().strip().replace(",", ".")
        try:
            return float(s) if s else 0.0
        except ValueError:
            return 0.0

    def _video_kivalaszt(self):
        """A kijelolesehez hasznalt videó (mappa eseten az elso fajl)."""
        p = self.utvonal.get().strip()
        video = p
        if os.path.isdir(p):
            v = [f for f in sorted(os.listdir(p))
                 if f.lower().endswith((".mp4", ".mov", ".avi", ".mkv"))]
            if not v:
                messagebox.showwarning("Nincs videó", "Ebben a mappában nincs videófájl.")
                return None
            video = os.path.join(p, v[0])
        if not os.path.isfile(video):
            messagebox.showwarning("Nincs videó", "Először válassz ki egy videót vagy mappát.")
            return None
        return video

    def _kijelol_ablak(self, video, mp, cim):
        """Kijelolo ablak a videó adott masodpercenel levo kepkockan."""
        try:
            fr = mh.kepkocka(video, mp)
        except Exception as ex:
            messagebox.showerror("Hiba", str(ex))
            return None
        messagebox.showinfo("Kijelölés",
                            "Most felugrik egy ablak a videó %s pontjánál\n"
                            "lévő képkockával.\n\n"
                            "1. Húzz téglalapot a KABÓCA TESTE köré\n"
                            "   (a potrohvég is legyen benne).\n"
                            "2. Nyomj ENTER-t vagy SPACE-t.\n\n"
                            "Ha mégsem: nyomj c-t." % mh.ido(mp))
        cv2.namedWindow(cim, cv2.WINDOW_NORMAL)
        bbox = cv2.selectROI(cim, fr, showCrosshair=True, fromCenter=False)
        cv2.destroyAllWindows()
        for _ in range(4):
            cv2.waitKey(1)
        if bbox[2] < 5 or bbox[3] < 5:
            return None
        return tuple(int(v) for v in bbox)

    # ------------------------------------------------------ ellenorzopontok
    def _epont_lista_frissit(self):
        self.epont_lista.delete(0, "end")
        for mp, bb in self.epontok:
            self.epont_lista.insert("end", "%9s   x=%4d y=%4d  %dx%d"
                                   % (mh.ido(mp), bb[0], bb[1], bb[2], bb[3]))

    def _epont_hozzaad(self):
        video = self._video_kivalaszt()
        if not video:
            return
        mp = simpledialog.askfloat("Ellenőrzőpont",
                                   "A videó melyik másodpercénél jelöljük ki újra\n"
                                   "a kabócát?\n\n"
                                   "(Például 900 = 15 perc, 1800 = 30 perc)",
                                   parent=self, minvalue=0.0)
        if mp is None:
            return
        bb = self._kijelol_ablak(video, mp,
                                 "Ellenorzopont - jelold ki a kabocat - ENTER = kesz")
        if bb is None:
            return
        self.epontok = [e for e in self.epontok if abs(e[0] - mp) > 0.01]
        self.epontok.append((mp, bb))
        self.epontok.sort()
        self._epont_lista_frissit()

    def _epont_torol(self):
        sel = list(self.epont_lista.curselection())
        for i in reversed(sel):
            del self.epontok[i]
        self._epont_lista_frissit()

    def _epont_mind_torol(self):
        self.epontok = []
        self._epont_lista_frissit()

    def _roi_kijelol(self):
        video = self._video_kivalaszt()
        if not video:
            return
        mp = self._kezdo_masodperc()
        bbox = self._kijelol_ablak(video, mp, "Jelold ki a kabocat - ENTER = kesz")
        if bbox is None:
            self.roi_torles_csendes()
            return
        self.roi = bbox
        self.roi_cimke.configure(
            text="Kijelölve (%s): x=%d y=%d %dx%d"
                 % (mh.ido(mp), bbox[0], bbox[1], bbox[2], bbox[3]),
            foreground="#136d13")

    def _roi_torles(self):
        self.roi_torles_csendes()

    def roi_torles_csendes(self):
        self.roi = None
        self.roi_cimke.configure(text="Még nincs kijelölve (mappás módban\n"
                                      "videónként fel fog ugrani az ablak).",
                                 foreground="#8a6d00")

    def _sor_megnyit(self, _evt):
        self._megnyit_kijelolt("_kep")

    def _megnyit_kijelolt(self, kulcs):
        sel = self.tabla.selection()
        if not sel:
            messagebox.showinfo("Nincs kijelölés", "Válassz ki egy sort a listából.")
            return
        i = int(sel[0])
        megnyit(self.talalatok[i].get(kulcs, ""))

    def _mappa_megnyit(self):
        megnyit(self.kimenet_mappa or self.kimenet.get())

    def _csv_megnyit(self):
        m = self.kimenet_mappa or self.kimenet.get()
        megnyit(os.path.join(m, "mezharmat_esemenyek.csv"))

    # ------------------------------------------------------------------ futás
    def _log(self, *a):
        self.uzenetek.put(("log", " ".join(str(x) for x in a)))

    def _indit(self):
        p = self.utvonal.get().strip()
        if not p or not os.path.exists(p):
            messagebox.showwarning("Hiányzó adat", "Válassz ki egy videót vagy mappát.")
            return
        kimenet = self.kimenet.get().strip()
        if not kimenet:
            alap = p if os.path.isdir(p) else os.path.dirname(p)
            kimenet = os.path.join(os.path.dirname(alap) or alap, "output")
            self.kimenet.set(kimenet)
        try:
            os.makedirs(kimenet, exist_ok=True)
        except Exception as ex:
            messagebox.showerror("Hiba", "Nem hozható létre az eredménymappa:\n%s" % ex)
            return

        def szam(sv):
            s = sv.get().strip().replace(",", ".")
            return float(s) if s else None
        try:
            tol, ig = szam(self.tol), szam(self.ig)
        except ValueError:
            messagebox.showwarning("Hibás érték", "A másodperc mezőkbe számot írj.")
            return

        mh.beallit(self.erz.get())
        self.kimenet_mappa = kimenet
        self.talalatok = []
        self.tabla.delete(*self.tabla.get_children())
        self.naplo.configure(state="normal"); self.naplo.delete("1.0", "end")
        self.naplo.configure(state="disabled")
        self.leallitas.clear()
        self.indit_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        self.folyamat.configure(value=0)

        if os.path.isdir(p):
            videok = [os.path.join(p, f) for f in sorted(os.listdir(p))
                      if f.lower().endswith((".mp4", ".mov", ".avi", ".mkv"))]
        else:
            videok = [p]

        self.szal = threading.Thread(target=self._munka,
                                     args=(videok, kimenet, tol, ig, self.klip.get()),
                                     daemon=True)
        self.szal.start()

    def _munka(self, videok, kimenet, tol, ig, klip):
        mind = []
        try:
            self._log("Feldolgozandó videók: %d db" % len(videok))
            for n, v in enumerate(videok, 1):
                if self.leallitas.is_set():
                    break
                self.uzenetek.put(("allapot", "%d/%d videó..." % (n, len(videok))))
                roi = self.roi
                if roi is None:
                    self.roi_kesz = threading.Event()
                    self.roi_valasz = None
                    self.uzenetek.put(("roi_keres", v))
                    self.roi_kesz.wait()
                    roi = self.roi_valasz
                    if roi is None:
                        self._log("   Kihagyva (nem jelöltél ki területet).")
                        continue

                def prog(frac, mp, n=n):
                    alap = (n - 1) / len(videok)
                    self.uzenetek.put(("progress", (alap + frac / len(videok)) * 1000))
                    self.uzenetek.put(("allapot", "%d/%d - %s" % (n, len(videok), mh.ido(mp))))

                sorok = mh.feldolgoz(v, kimenet, roi, tol, ig, klip,
                                     log=self._log, progress=prog,
                                     stop=self.leallitas.is_set,
                                     ellenorzopontok=self.epontok)
                mind += sorok
                for s in sorok:
                    self.uzenetek.put(("talalat", s))
            csv_ut = os.path.join(kimenet, "mezharmat_esemenyek.csv")
            mh.csv_ir(csv_ut, mind)
            self._log("")
            self._log("KÉSZ. Összes találat: %d" % len(mind))
            self._log("CSV: %s" % csv_ut)
        except Exception as ex:
            self._log("HIBA: %s" % ex)
            self.uzenetek.put(("hiba", str(ex)))
        finally:
            self.uzenetek.put(("vege", None))

    # -------------------------------------------------- követés ellenőrzése
    def _kovetes_ellenoriz(self):
        video = self._video_kivalaszt()
        if not video:
            return
        if self.roi is None:
            messagebox.showwarning("Nincs kijelölés",
                                   "Először jelöld ki a kabócát a 2. pontban.")
            return
        kimenet = self.kimenet.get().strip()
        if not kimenet:
            alap = os.path.dirname(video)
            kimenet = os.path.join(os.path.dirname(alap) or alap, "output")
            self.kimenet.set(kimenet)
        try:
            os.makedirs(kimenet, exist_ok=True)
        except Exception as ex:
            messagebox.showerror("Hiba", "Nem hozható létre az eredménymappa:\n%s" % ex)
            return

        def szam(sv):
            s = sv.get().strip().replace(",", ".")
            return float(s) if s else None
        try:
            tol, ig = szam(self.tol), szam(self.ig)
        except ValueError:
            messagebox.showwarning("Hibás érték", "A másodperc mezőkbe számot írj.")
            return

        self.kimenet_mappa = kimenet
        self.leallitas.clear()
        self.indit_btn.configure(state="disabled")
        self.kovetes_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        self.folyamat.configure(value=0)
        self.naplo.configure(state="normal"); self.naplo.delete("1.0", "end")
        self.naplo.configure(state="disabled")

        def munka():
            try:
                self._log("Követés-ellenőrző videó készítése...")
                self._log("A zöld keret = biztos követés, a vörös = bizonytalan.")

                def prog(frac, mp):
                    self.uzenetek.put(("progress", frac * 1000))
                    self.uzenetek.put(("allapot", "Követés: %s" % mh.ido(mp)))

                ut, szaz = mh.kovetes_video(video, self.roi, kimenet, tol, ig,
                                            self.epontok, 6, log=self._log,
                                            progress=prog, stop=self.leallitas.is_set)
                self._log("")
                self._log("KÉSZ. Nyisd meg és nézd át: %s" % ut)
                self.uzenetek.put(("kovetes_kesz", (ut, szaz)))
            except Exception as ex:
                self._log("HIBA: %s" % ex)
                self.uzenetek.put(("hiba", str(ex)))
            finally:
                self.uzenetek.put(("vege", None))

        self.szal = threading.Thread(target=munka, daemon=True)
        self.szal.start()

    def _stop(self):
        self.leallitas.set()
        self.allapot.configure(text="Leállítás...")

    def _uzenet_pumpa(self):
        try:
            while True:
                tipus, adat = self.uzenetek.get_nowait()
                if tipus == "log":
                    self.naplo.configure(state="normal")
                    self.naplo.insert("end", adat + "\n")
                    self.naplo.see("end")
                    self.naplo.configure(state="disabled")
                elif tipus == "progress":
                    self.folyamat.configure(value=min(adat, 1000.0))
                elif tipus == "allapot":
                    self.allapot.configure(text=adat)
                elif tipus == "talalat":
                    i = len(self.talalatok)
                    self.talalatok.append(adat)
                    self.tabla.insert("", "end", iid=str(i), tags=(adat["megbizhatosag"],),
                                      values=(adat["kezdet"], adat["veg"], adat["pontszam"],
                                              "erős" if adat["megbizhatosag"] == "eros" else "gyenge",
                                              adat["csepp_terulet"], adat["video"]))
                elif tipus == "roi_keres":
                    self._roi_futas_kozben(adat)
                elif tipus == "kovetes_kesz":
                    ut, szaz = adat
                    if messagebox.askyesno("Követés-ellenőrző videó kész",
                                           "Bizonytalan képkockák: %.1f%%\n\n"
                                           "Megnyitod a videót?" % szaz):
                        megnyit(ut)
                elif tipus == "hiba":
                    messagebox.showerror("Hiba", adat)
                elif tipus == "vege":
                    self.indit_btn.configure(state="normal")
                    self.kovetes_btn.configure(state="normal")
                    self.stop_btn.configure(state="disabled")
                    self.folyamat.configure(value=1000.0)
                    self.allapot.configure(text="Kész.")
        except queue.Empty:
            pass
        self.after(120, self._uzenet_pumpa)

    def _roi_futas_kozben(self, video):
        """Mappás módban videónként bekéri a ROI-t a fő szálon."""
        try:
            mp = self._kezdo_masodperc()
            try:
                fr = mh.kepkocka(video, mp)
                ok = True
            except Exception:
                ok, fr = False, None
            if ok:
                messagebox.showinfo("Kijelölés",
                                    "Következő videó:\n%s\n\n"
                                    "A képkocka a videó %s pontjából van.\n\n"
                                    "Húzz téglalapot a kabóca teste köré,\n"
                                    "majd nyomj ENTER-t."
                                    % (os.path.basename(video), mh.ido(mp)))
                cv2.namedWindow("Jelold ki a kabocat - ENTER = kesz", cv2.WINDOW_NORMAL)
                b = cv2.selectROI("Jelold ki a kabocat - ENTER = kesz", fr,
                                  showCrosshair=True, fromCenter=False)
                cv2.destroyAllWindows()
                for _ in range(4):
                    cv2.waitKey(1)
                if b[2] >= 5 and b[3] >= 5:
                    self.roi_valasz = tuple(int(v) for v in b)
        finally:
            self.roi_kesz.set()

    def _kilepes(self):
        if self.szal and self.szal.is_alive():
            if not messagebox.askyesno("Kilépés", "Fut a feldolgozás. Biztosan kilépsz?"):
                return
            self.leallitas.set()
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
