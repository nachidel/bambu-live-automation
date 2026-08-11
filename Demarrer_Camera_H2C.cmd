@echo off
setlocal

title Bambu H2C Camera Bridge

set "CAM=%APPDATA%\BambuStudioBeta\cameratools"
set "URLPATH=%APPDATA:\=/%/BambuStudioBeta/cameratools/url.txt"
set "LOG=%TEMP%\bambu-source.log"

if not exist "%CAM%\bambu_source.exe" (
    echo ERREUR : bambu_source.exe introuvable :
    echo %CAM%\bambu_source.exe
    pause
    exit /b 1
)

if not exist "%CAM%\ffmpeg.exe" (
    echo ERREUR : ffmpeg.exe introuvable :
    echo %CAM%\ffmpeg.exe
    pause
    exit /b 1
)

if not exist "%CAM%\url.txt" (
    echo ERREUR : url.txt introuvable :
    echo %CAM%\url.txt
    pause
    exit /b 1
)

echo Demarrage du flux camera H2C...
echo Sortie RTP : 127.0.0.1:1234
echo Log Bambu : %LOG%
echo.
echo Pour arreter le flux, fermez cette fenetre ou faites Ctrl+C.
echo.

"%CAM%\bambu_source.exe" "bambu:///camera/%URLPATH%" 2>"%LOG%" | "%CAM%\ffmpeg.exe" -fflags nobuffer -flags low_delay -analyzeduration 10 -probesize 3200 -f h264 -i pipe: -vcodec copy -f rtp rtp://127.0.0.1:1234

echo.
echo Le flux camera est arrete.
pause

endlocal
