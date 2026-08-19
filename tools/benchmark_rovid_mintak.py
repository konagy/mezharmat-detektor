"""Reprodukalhato meres az ot, repón kivul tarolt rovid mintavideon."""

import argparse
import os
import sys


GYOKER = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, GYOKER)

import mezharmat as mh  # noqa: E402


MINTAK = {
    1: dict(test=(685, 400, 190, 135), csepp=(630, 405, 105, 120),
            vart=(11.5, 13.5)),
    2: dict(test=(110, 485, 240, 160), csepp=(60, 500, 125, 145),
            vart=(2.5, 3.8)),
    3: dict(test=(110, 480, 245, 165), csepp=(55, 495, 130, 150),
            vart=(8.5, 10.5)),
    4: dict(test=(340, 480, 240, 160), csepp=(285, 495, 130, 145),
            vart=None),
    5: dict(test=(340, 480, 240, 160), csepp=(285, 495, 130, 145),
            vart=None),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("forras", help="Az 1.mp4 ... 5.mp4 fajlokat tartalmazo mappa")
    args = ap.parse_args()

    hibak = []
    print("video | vart | eros talalatok | eredmeny")
    print("------|------|-----------------|---------")
    for sorszam, minta in MINTAK.items():
        video = os.path.join(args.forras, "%d.mp4" % sorszam)
        if not os.path.isfile(video):
            hibak.append("Hianyzik: %s" % video)
            continue
        _, esemenyek, _ = mh.elemez(video, minta["test"], log=lambda *_: None,
                                    csepp_roi=minta["csepp"])
        eros = [e for e in esemenyek if e["megbizhatosag"] == "eros"]
        idok = ["%.2f-%.2f (%.0f)" % (e["start_s"], e["end_s"], e["score"])
                for e in eros]
        if minta["vart"] is None:
            jo = not eros
            vart = "nincs"
        else:
            a, b = minta["vart"]
            jo = any(e["start_s"] <= b and e["end_s"] >= a for e in eros)
            vart = "%.1f-%.1fs" % (a, b)
        print("%d.mp4 | %s | %s | %s" %
              (sorszam, vart, ", ".join(idok) or "nincs", "OK" if jo else "HIBA"))
        if not jo:
            hibak.append("%d.mp4 nem teljesitette az elvart eredmenyt" % sorszam)

    if hibak:
        raise SystemExit("\n".join(hibak))


if __name__ == "__main__":
    main()
