@echo off
REM ============================================================
REM  Mezharmat Detektor - inditas
REM  Tedd ezt a fajlt ugyanabba a mappaba, mint a
REM  Mezharmat_Detektor.py es a mezharmat.py fajlokat,
REM  aztan csak dupla klikk erre.
REM ============================================================
cd /d "%~dp0"

py -c "import cv2" 2>nul
if errorlevel 1 (
    echo Az OpenCV nincs telepitve. Most telepitem...
    py -m pip install --upgrade pip
    if exist requirements.txt (
        py -m pip install -r requirements.txt
    ) else (
        py -m pip install opencv-python numpy
    )
    if errorlevel 1 (
        echo.
        echo A telepites nem sikerult. Nezd meg a fenti hibat.
        pause
        exit /b 1
    )
)

py Mezharmat_Detektor.py
if errorlevel 1 (
    echo.
    echo A program hibaval leallt. A fenti sorok mutatjak, mi tortent.
    pause
)
