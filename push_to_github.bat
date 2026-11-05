@echo off
setlocal EnableDelayedExpansion
title BITS Tandem Centre - Push to GitHub
cd /d "%~dp0"

set "REPO_URL=https://github.com/smartboyhacker66-stack/Ndrccrcs.git"
set "REPO_WEB=https://github.com/smartboyhacker66-stack/Ndrccrcs"
set "REPO_SLUG=smartboyhacker66-stack/Ndrccrcs"
set "SERVER_HOST=bits-tandem-centre.onrender.com"
set "SERVER_URL=https://%SERVER_HOST%"
set "APK_URL=%REPO_WEB%/releases/latest/download/BITS-Tandem-Centre.apk"
set "ACTIONS_URL=%REPO_WEB%/actions"
set "BLUEPRINT_URL=https://dashboard.render.com/blueprint/new?repo=%REPO_WEB%"

echo ============================================================
echo   BITS Tandem Centre - master push script
echo   Developed by Blind Indian Tech Support
echo ============================================================
echo.

where git >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Git is not installed. Install it from https://git-scm.com/download/win and run this again.
    pause
    exit /b 1
)

echo [1/6] Preparing the Git repository...
if not exist ".git" git init
git branch -M main

git remote get-url origin >nul 2>nul
if errorlevel 1 (
    git remote add origin %REPO_URL%
) else (
    git remote set-url origin %REPO_URL%
)

set "GN="
for /f "delims=" %%i in ('git config user.name 2^>nul') do set "GN=%%i"
if not defined GN git config user.name "Blind Indian Tech Support"
set "GE="
for /f "delims=" %%i in ('git config user.email 2^>nul') do set "GE=%%i"
if not defined GE git config user.email "noreply@users.noreply.github.com"

echo.
echo [2/6] Committing all files...
git add -A
git diff --cached --quiet
if errorlevel 1 (
    git commit -m "BITS Tandem Centre update"
    if errorlevel 1 (
        echo [ERROR] Commit failed.
        pause
        exit /b 1
    )
) else (
    echo   Nothing new to commit. Pushing the existing commit again.
)

echo.
echo [3/6] Pushing to main on GitHub (this starts the APK build)...
git push -u origin main
if errorlevel 1 (
    echo   Push was rejected. Trying to combine with what is already on GitHub...
    git pull origin main --rebase --allow-unrelated-histories
    if errorlevel 1 (
        echo [ERROR] Could not combine with the remote history. Resolve the conflicts shown above, then run this again.
        pause
        exit /b 1
    )
    git push -u origin main
    if errorlevel 1 (
        echo [ERROR] Push failed. Check that you are signed in to GitHub and can write to %REPO_SLUG%.
        pause
        exit /b 1
    )
)

set "SHA="
for /f "delims=" %%i in ('git rev-parse HEAD') do set "SHA=%%i"

echo.
echo [4/6] Triggering the Render deployment...
if defined RENDER_DEPLOY_HOOK (
    echo   Calling your Render deploy hook...
    curl.exe -s -X POST "%RENDER_DEPLOY_HOOK%" >nul
    echo   Deploy requested.
) else if not exist ".render_blueprint_opened" (
    echo   First run: opening Render so it can read render.yaml from the repository.
    echo   Sign in, press "Apply", and Render builds with npm install and starts with node server.js.
    echo   After this one-time step, every push deploys automatically.
    start "" "%BLUEPRINT_URL%"
    echo opened> ".render_blueprint_opened"
) else (
    echo   Render is already linked. The push above triggers its automatic deploy.
)

echo.
echo [5/6] Waiting for the live server to answer HTTP 200 (Render free tier can take a minute)...
set /a TRIES=0
:waitserver
set "CODE="
for /f %%c in ('curl.exe -s -o nul -w "%%{http_code}" --max-time 20 "%SERVER_URL%/health"') do set "CODE=%%c"
if "!CODE!"=="200" goto serverok
set /a TRIES+=1
if !TRIES! GEQ 24 goto servertimeout
echo   Server not ready yet ^(attempt !TRIES! of 24^)...
timeout /t 10 /nobreak >nul
goto waitserver

:serverok
echo   Server is live.
set "SERVER_STATE=LIVE"
goto buildwait

:servertimeout
echo   The server did not answer in time. If you have not completed the Render step yet, finish it and check the link below later.
set "SERVER_STATE=NOT RESPONDING YET"

:buildwait
echo.
echo [6/6] Waiting for GitHub to build the APK...
if exist ".build_result.txt" del ".build_result.txt"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\wait-for-build.ps1" -Sha %SHA% -Repo %REPO_SLUG%
set "BUILD=unavailable"
if exist ".build_result.txt" set /p BUILD=<".build_result.txt"

set "APK_STATE=not downloaded"
if /i "!BUILD!"=="success" (
    if not exist "dist" mkdir "dist"
    curl.exe -s -L -o "dist\BITS-Tandem-Centre.apk" "%APK_URL%"
    if exist "dist\BITS-Tandem-Centre.apk" set "APK_STATE=dist\BITS-Tandem-Centre.apk"
)

echo.
echo ============================================================
echo   RESULTS
echo ============================================================
echo   Live server  : %SERVER_URL%   [!SERVER_STATE!]
echo   Health check : %SERVER_URL%/health
echo   Build result : !BUILD!
echo   APK download : %APK_URL%
echo   Saved on disk: !APK_STATE!
echo   Build logs   : %ACTIONS_URL%
echo   Render setup : %BLUEPRINT_URL%
echo ============================================================
if /i not "!BUILD!"=="success" (
    echo.
    echo   The APK was not confirmed. Open the build logs link above to see the build.
)
echo.
pause
endlocal
