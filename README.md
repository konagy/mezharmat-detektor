# Mézharmat-detektor – használati útmutató

Ez a program kabócafelvételeken keresi a **mézharmat-cseppek megjelenését** a potrohvégen,
és időbélyeges listát + ellenőrző klipeket készít.

Két módon használható:

- **Asztali alkalmazás** (`Mezharmat_Detektor.py`) – kattintgatható felület, ezt ajánlom.
- **Parancssorból** (`mezharmat.py`) – ha automatizálni akarod.

A sandboxomban a te öt mintavideódon **be van kalibrálva és le van tesztelve**.

---

## 1. Telepítés (egyszer, kb. 2 perc)

Hozz létre egy mappát, pl. `C:\mezharmat_detektor`, és tedd bele mind a három fájlt:

```
C:\mezharmat_detektor\
    Mezharmat_Detektor.py    <- az alkalmazás
    mezharmat.py             <- a detektor motor (ez kell hozzá)
    INDITAS.bat              <- indító
    videos\                  <- ide a videók
    output\                  <- ide kerülnek az eredmények (magától elkészül)
```

Utána **dupla klikk az `INDITAS.bat` fájlra**. Ha az OpenCV még nincs telepítve,
magától telepíti, aztán elindul az alkalmazás.

Ha kézzel akarod: parancssorban `py -m pip install opencv-python numpy`.

A Tkinter (a felület) nem igényel telepítést, a Windows-os Pythonnal együtt jön.

---

## 2. Az alkalmazás használata

A felület négy lépésre van osztva, felülről lefelé:

### 1. Melyik videót vizsgáljuk?

Válaszd az **Egy videófájl** vagy az **Egy egész mappa** módot, majd **Tallózás**.
Mappás módban sorra veszi az összes videót.

### 2. Hol van a kabóca?

Kattints a **Kabóca kijelölése** gombra:

- húzz téglalapot **a kabóca teste köré** (a potrohvég legyen benne),
- nyomj **ENTER**-t vagy **SPACE**-t.

**Fontos újítás:** a kijelölés nem az első képkockán történik, hanem **annál a
másodpercnél, ahol az elemzés kezdődik**. Ha tehát a 3. pontban a *Csak ettől (mp)*
mezőbe `30`-at írsz, akkor a 30. másodperc képkockáján kell kijelölnöd a kabócát –
ott, ahol valóban van. (Írd be először az időt, aztán jelöld ki!)

A zöld szövegben látszik, melyik időpontból van a kijelölés.

Mappás módban kihagyhatod ezt a lépést: akkor videónként fel fog ugrani a kijelölő ablak.

### 2b. Ellenőrzőpontok – hosszú videóhoz

Több órás videónál a követés idővel elcsúszhat (erről lásd a 8. pontot).
Az **Ellenőrzőpont hozzáadása** gombbal megadhatod, hogy **a videó egy adott
másodpercénél hol van éppen a kabóca**:

1. megkérdezi, melyik másodpercnél (pl. `900` = 15 perc),
2. felugrik az ahhoz tartozó képkocka, ott újra kijelölöd az állatot,
3. a pont bekerül a listába.

A program ezeknél az időpontoknál **visszaállítja a keretet a megadott helyre**, és
onnan folytatja a követést. Ez a legbiztosabb módszer hosszú felvételnél:
**10–15 percenként egy ellenőrzőpont** gyakorlatilag kizárja, hogy a keret
végleg elveszítse az állatot.

Ellenőrzőpontot annyit adhatsz meg, amennyit akarsz. A *Kijelölt törlése* és
*Összes törlése* gombokkal lehet javítani a listán.

### 3. Beállítások

| Beállítás | Mire jó |
|---|---|
| **Szigorú** | kevesebb téves riasztás, de egy halvány cseppet elszalaszthat |
| **Alap** | erre kalibráltam a mintáidon – ezzel kezdd |
| **Érzékeny** | több találatot ad, de több téves riasztást is |
| **Csak ettől / eddig (mp)** | csak a videó egy szakaszát vizsgálja |
| **Ellenőrző klipek és képek** | ha kikapcsolod, gyorsabb, de nem tudod gyorsan ellenőrizni |
| **Eredmények mappája** | ide kerül a CSV, a klipek és a képek |

### 4. Indítás és eredmények

Kattints a **Keresés indítása** gombra. A felület nem fagy le:

- a **folyamatjelző** és a mellette lévő szöveg mutatja, hol tart,
- a **Leállítás** gombbal bármikor megszakíthatod (az addigi találatok megmaradnak),
- a **napló** alul mutatja, mi történik.

Ahogy talál valamit, azonnal megjelenik a **Találatok** táblázatban:

- **zöld** sor = erős találat (nagy valószínűséggel valódi mézharmat),
- **sárgás** sor = gyenge találat (bizonytalan, érdemes ránézni).

Egy sorra **duplán kattintva** megnyílik a hozzá tartozó ránagyított kép.
A jobb oldali gombokkal a klipet, az eredménymappát vagy a CSV-t nyithatod meg.

A napló minden futás végén kiírja a **követés minőségét**:

```
KOVETES: atlagos egyezes 0.99 | bizonytalan kepkockak: 0.4%
```

Ha a bizonytalan képkockák aránya 20% fölé megy, figyelmeztetést is ír. Ilyenkor
ne bízz a találatokban: nézd meg a követés-ellenőrző videót, és tegyél be
ellenőrzőpontokat.

### Követés ellenőrzése (új gomb)

A **Követés ellenőrzése** gomb nem keres mézharmatot, hanem készít egy videót,
amin **rajta van a keret**, hogy a saját szemeddel láthasd, követi-e a program
az állatot:

- **zöld keret** = biztos követés,
- **vörös keret** = bizonytalan (a program nem találja meg jól a kabócát),
- a bal felső sarokban látszik az idő és az egyezés értéke.

A videó hatszoros sebességgel készül, tehát egy óra felvétel kb. 10 perc alatt
átnézhető. **Egy hosszú felvétel elemzése előtt érdemes ezzel ellenőrizni**, hogy
jó-e a kijelölés és elég-e az ellenőrzőpont.

---

## 3. A 6 órás videó – ajánlott menet

**1. Először szakaszonként dolgozz.** Írd a **Csak ettől** mezőbe `0`-t, az **eddig**
mezőbe `3600`-at, és indítsd el. Ha jók a találatok, mehet a többi szakasz
(`3600`–`7200`, stb.). Minden szakasznál a kijelölés a szakasz kezdeténél lévő
képkockán történik, tehát mindig ott jelölöd ki a kabócát, ahol éppen van.

**2. Tegyél be ellenőrzőpontokat.** A 2b. pont szerint 10–15 percenként add meg,
hol van a kabóca. Egy órás szakaszhoz 4–6 ellenőrzőpont elég. Ez a legbiztosabb
védelem az elcsúszás ellen.

**3. Előbb ellenőrizd a követést.** A **Követés ellenőrzése** gombbal készíts egy
gyorsított videót, és nézd át, hogy a keret rajta van-e az állaton. Ha sok a vörös
keret, tegyél be több ellenőrzőpontot, és csak utána indítsd a keresést.

Ez azért jobb, mint egyben végigfuttatni:

- hamarabb látod, jó-e a kijelölés,
- ha a kabóca közben nagyot mozdul vagy más szögbe fordul, szakaszonként újra ki tudod jelölni,
- nem veszik el minden, ha valami félbeszakad.

**Futásidő:** a javított követő pontosabb, de lassabb. A mérésem szerint a saját
gépemen a puszta követés 6 óra videóra kb. **1,8 óra**, a teljes elemzés
becslésem szerint **3,5–4,5 óra**. A te gépeden ez lehet több vagy kevesebb.
Futás közben a gép használható, csak lassabb lesz.

---

## 4. Az eredmény értelmezése

A CSV oszlopai:

- `kezdet`, `veg` – az esemény időtartama (óó:pp:mm.ezred)
- `hossz_kepkocka` – hány képkockán át látszott a csepp
- `csepp_terulet` – a folt legnagyobb mérete pixelben
- `fenyesseg` – mennyivel volt világosabb a háttérnél
- `kovetes_egyezes` – mennyire volt biztos a követés az esemény alatt (1,00 = teljesen
  biztos). Ha ez 0,60 alatti, a találat gyanús: lehet, hogy a keret nem is a kabócán volt.
- `pontszam` – összesített erősség (hossz × fényesség)
- `megbizhatosag` – `eros` vagy `gyenge`

**Munkamenet javaslat:** először csak az **erős** sorokat nézd át a képeken.
A gyenge sorok a bizonytalan esetek – ezeket érdemes átfutni, de itt lesz a
legtöbb téves riasztás.

A te mintáidon mért értékek (referencia):

| Minta | Valóság | Program eredménye |
|---|---|---|
| 17 s-es klip | mézharmat ~11,8 s | **12,26–13,03 s, pontszám 875, erős** |
| 5 s-es klip | mézharmat ~3,0 s | **3,05–3,35 s, pontszám 424, erős** |
| 12 s-es klip (homályos) | mézharmat ~8,8 s | **9,68–10,92 s, pontszám 653, erős** |
| 41 s-es klip (tőzeglégy) | nincs mézharmat | 2 gyenge jelölés (246 és 124) |
| 10 s-es klip (tőzeglégy + heves mozgás) | nincs mézharmat | **1 téves ERŐS jelölés (410)** |

**Mindhárom valódi mézharmatot megtalálja** (424, 653, 875 pont).

**Egy visszalépésről őszintén:** a követő javítása előtt a 10 s-es negatív minta
349 pontot kapott (gyenge), most 410-et (erős). Az ok: az új követő itt valóban
elmozdul kb. 18 pixelt, mert a heves mozgásnál az állat megmozdul – ez helyes
követés, de a háttérszámítást felkavarja, és felviszi a téves esemény pontszámát.
Megvizsgáltam, hogy a folt elmozdulása alapján ki lehet-e szűrni: **nem**, mert
ez a téves folt épp a legkevésbé mozog (0,7 px, a valódi cseppek 3,9–6,3 px).

Ennek gyakorlati következménye:

- a határ most **410 és 424 között** van, ami szűk. Szűrésnek használd a
  pontszámot, de **minden erős találatot nézz meg a klipen** – a program
  előszűrő, nem végleges döntés.
- ha nagyon zavar a téves riasztás, a **Szigorú** beállítás a mintáimon
  mindkét negatívot kiszűrte, viszont a leghalványabb valódi cseppet is
  elvesztette (424 → 357, azaz gyenge lett). Ez a kompromisszum.

---

## 5. Hangolás, ha kell

A `mezharmat.py` fájl elején, a `P = dict(...)` blokkban:

- `BRIGHT_DELTA` – mennyivel legyen világosabb a csepp a háttérnél (kisebb = érzékenyebb)
- `MIN_PERSIST` – hány képkockán át kell látszania (kisebb = érzékenyebb)
- `MAX_AREA` – a legnagyobb elfogadott folt (nagyobb = nagyobb cseppek is beleférnek)
- `MOTION_MAX` – e fölötti mozgásnál a képkocka kimarad (nagyobb = mozgás közben is néz)
- `MIN_SCORE` – innen számít „erős” találatnak

---

## 6. Parancssoros használat (opcionális)

```
cd C:\mezharmat_detektor
py mezharmat.py --mappa "C:\mezharmat_detektor\videos"
py mezharmat.py --video "...\hosszu.mp4" --tol 0 --ig 3600
py mezharmat.py --video "...\hosszu.mp4" --roi 686,402,181,128 --szigoru
```

Ellenőrzőpontokkal és követés-ellenőrző videóval:

```
py mezharmat.py --video "...\hosszu.mp4" --tol 0 --ig 3600 ^
   --ellenorzopont 900:640,400,180,130 ^
   --ellenorzopont 1800:700,410,175,128

py mezharmat.py --video "...\hosszu.mp4" --kovetes-video --roi 686,402,181,128
```

Kapcsolók: `--video`, `--mappa`, `--kimenet`, `--roi x,y,w,h`, `--tol`, `--ig`,
`--erzekeny`, `--szigoru`, `--nincs-klip`,
`--ellenorzopont masodperc:x,y,w,h` (többször is megadható),
`--kovetes-video` (csak ellenőrző videót készít), `--gyorsitas 6`.

Ha rossz fájlnevet adsz meg, a program kilistázza, milyen videók vannak a mappában.

---

## 7. Ha önálló .exe-t szeretnél

Nem kötelező, de ha másnak is odaadnád Python nélkül:

```
py -m pip install pyinstaller
cd C:\mezharmat_detektor
pyinstaller --onefile --windowed --name "Mezharmat Detektor" ^
            --add-data "mezharmat.py;." Mezharmat_Detektor.py
```

Az elkészült `.exe` a `dist` mappában lesz. Nagy lesz (kb. 100–200 MB), mert az OpenCV
is bele van csomagolva, és az első indítás lassabb.

---

## 8. A követésről – mit mértem

A követőt szintetikus tesztekkel mértem: a valódi felvételből kivágtam az állatot,
és mesterségesen elugratom, elfordítom, átméretezem, hogy tudjam, mi a valódi helyes
válasz. Négy eset, összehasonlítva a régi és az új követőt:

| Eset | Ugrás | Elfordulás | Méret | Régi követő | Új követő |
|---|---|---|---|---|---|
| könnyű | 150×70 px | 0° | 1,00× | 19 képkocka alatt talál vissza, 11,9 px átlaghiba | **azonnal, 0,6 px** |
| közepes | 180×190 px | 12° | 1,12× | 19 képkocka, 27,5 px | **azonnal, 1,4 px** |
| nehéz | 260×300 px | 25° | 0,80× | **soha nem találja meg** (378 px) | **azonnal, 2,1 px** |
| nagyon nehéz | 420×420 px | 40° | 0,72× | **soha nem találja meg** (596 px) | **azonnal, 3,5 px** |

Mi volt a régi változat hibája? Amikor az állat elugrott, a háttéren maradt keret is
0,55 körüli „egyezést” kapott, ami átment a 0,38-as minimumon – így a program
**megtaláltnak hitte magát, és el sem indította a nagy keresést**. Ezért csendben
elveszett anélkül, hogy bármit jelzett volna. Most addig bővül a keresés (szűk sáv →
bővebb sáv → egész kép), amíg nincs meggyőző (0,78 fölötti) egyezés, és a keresés
**méretet és elfordulást is próbál**. Mérés szerint a helyes szög és méret együtt
0,90–0,97-es egyezést ad még 40 fokos elfordulásnál is – külön-külön csak 0,4–0,6-ot,
ezért kell a kettőt együtt próbálni.

**Ami továbbra sem megy:** ha az állat teljesen más pózba kerül (pl. hátulról látszik
a hasa helyett), vagy kimászik a képből és később visszatér máshogy. Ezekre nem a
követő a válasz, hanem az **ellenőrzőpont** (2b. pont). Ezért is fontos, hogy a
program most **kiírja a követés minőségét**, és figyelmeztet, ha bizonytalan volt.

---

## 9. Amit érdemes tudni a korlátokról

- A követő akkor a legjobb, ha a kabóca **lassan** mozdul. Ha kiugrik a képből és
  máshol jelenik meg, a program 20 sikertelen képkocka után **automatikusan újra
  megkeresi** – de ha közben teljesen más szögből látszik, érdemes új szakaszt
  indítani friss kijelöléssel.
- A **harmatos, csillogó levél** okozhat téves riasztást (a 10 s-es mintán is ez történt).
  Ezt a pontszám és a kép alapján gyorsan ki lehet szűrni.
- Ez egy **szűrő eszköz**: a 6 órából csinál néhány tucat ellenőrizendő pillanatot.
  A végső döntés marad emberi – de a favágást elvégzi helyetted.
