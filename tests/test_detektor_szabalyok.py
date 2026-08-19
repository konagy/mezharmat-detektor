import math

import mezharmat as mh


def setup_function():
    mh.beallit("alap")


def test_csepp_roi_a_testhez_viszonyitva_mozog():
    relativ = mh.csepp_roi_relativ((100, 200, 200, 100), (50, 210, 100, 80))

    assert relativ == (-0.25, 0.1, 0.5, 0.8)
    assert mh._csepp_kornyezet((300, 400, 100, 50), (720, 1280), relativ) == (
        275, 405, 50, 40)


def test_kuszobok_30_fps_1280x720_eseten_megorzodnek():
    k = mh._futasi_kuszobok(30.0, (1280, 720))

    assert k["bg_len"] == 20
    assert k["min_area"] == 12
    assert k["max_area"] == 400
    assert k["max_step"] == 7.0
    assert k["min_persist"] == 7
    assert k["max_miss"] == 2


def test_kuszobok_kisebb_felbontashoz_es_fps_hez_skalazodnak():
    k = mh._futasi_kuszobok(15.0, (640, 480))

    assert k["bg_len"] == 10
    assert k["min_area"] == 4
    assert k["max_area"] == 133
    assert math.isclose(k["max_step"], 7.0 / math.sqrt(3.0), rel_tol=1e-6)
    assert k["min_persist"] == 4
    assert k["max_miss"] == 1


def test_alakpontszam_szuri_a_laza_foltot_de_megtartja_a_valodi_peldakat():
    _, eles_valodi = mh._esemeny_pontszam(10, 42.4, 0.957, 30.0)
    _, zavaro_folt = mh._esemeny_pontszam(8, 54.0, 0.829, 30.0)
    _, homalyos_valodi = mh._esemeny_pontszam(10, 56.3, 0.752, 30.0)

    assert eles_valodi >= mh.P["MIN_SCORE"]
    assert zavaro_folt < mh.P["MIN_SCORE"]
    assert homalyos_valodi >= mh.P["MIN_SCORE"]


def test_osszevonas_nem_csak_a_lista_elozo_elemet_nezi():
    def esemeny(x, y, score):
        return dict(start_s=1.0, end_s=1.2, x=x, y=y, score=score,
                    raw_score=score, frames=8, area=20, intensity=30.0,
                    kov_egyezes=0.95, box=(0, 0, 10, 10), fill=0.9,
                    korosseg=0.8, oldalarany=0.8)

    # A kozepso folt tavoli, de a harmadik az elso folytatasa.
    ossze = mh._esemenyek_osszevonasa([
        esemeny(70, 47, 500), esemeny(94, 52, 100), esemeny(78, 35, 800)
    ], max_step=7.0)

    assert len(ossze) == 2
    assert max(e["score"] for e in ossze) == 800
