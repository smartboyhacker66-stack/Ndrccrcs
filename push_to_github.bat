@echo off
setlocal EnableExtensions EnableDelayedExpansion
title BITS Tandem Centre - Push and Build
cd /d "%~dp0"

set "REPO_SLUG=smartboyhacker66-stack/Ndrccrcs"
set "REPO_URL=https://github.com/%REPO_SLUG%.git"
set "REPO_WEB=https://github.com/%REPO_SLUG%"
set "SERVER_URL=https://bits-tandem-centre.onrender.com"
set "BLUEPRINT_URL=https://dashboard.render.com/blueprint/new?repo=%REPO_WEB%"
set "WORKFLOW=build-apk.yml"
set "LOG=%TEMP%\bits_tandem_push.log"
set "ERRLOG=%~dp0build-error-log.txt"
set "GH_PROMPT_DISABLED=1"
set "GH_NO_UPDATE_NOTIFIER=1"
set "GIT_TERMINAL_PROMPT=0"
set "GIT_ADVICE=0"
set "OK=0"
set "RUNID="
set "SHA="
set "MSG="

echo.
echo ====================================================
echo   BITS Tandem Centre - one click push and build
echo   Developed by Blind Indian Tech Support
echo ====================================================
echo.

where git >nul 2>nul
if errorlevel 1 set "MSG=Git is not installed. Install it from https://git-scm.com/download/win" & goto :die
where gh >nul 2>nul
if errorlevel 1 set "MSG=GitHub CLI is not installed. Install it from https://cli.github.com then run: gh auth login" & goto :die
gh auth status >nul 2>nul
if errorlevel 1 set "MSG=GitHub CLI is not logged in. Run this once: gh auth login" & goto :die

> "%LOG%" echo BITS Tandem Centre push log

rem ======================================================
echo [Step 1/3] Saving your files and pushing them to GitHub...
rem ======================================================
if not exist ".git" git init -q >>"%LOG%" 2>&1
git config core.autocrlf false >>"%LOG%" 2>&1
git config core.safecrlf false >>"%LOG%" 2>&1
gh auth setup-git >>"%LOG%" 2>&1

git rev-parse --verify HEAD >nul 2>nul
if errorlevel 1 (git symbolic-ref HEAD refs/heads/main >>"%LOG%" 2>&1) else (git branch -M main >>"%LOG%" 2>&1)

git remote get-url origin >nul 2>nul
if errorlevel 1 (git remote add origin %REPO_URL% >>"%LOG%" 2>&1) else (git remote set-url origin %REPO_URL% >>"%LOG%" 2>&1)

set "GN="
for /f "delims=" %%i in ('git config user.name 2^>nul') do set "GN=%%i"
if not defined GN git config user.name "Blind Indian Tech Support" >>"%LOG%" 2>&1
set "GE="
for /f "delims=" %%i in ('git config user.email 2^>nul') do set "GE=%%i"
if not defined GE git config user.email "noreply@users.noreply.github.com" >>"%LOG%" 2>&1

git add -A >>"%LOG%" 2>&1
git diff --cached --quiet
if not errorlevel 1 goto :nocommit
git commit -q -m "BITS Tandem Centre update" >>"%LOG%" 2>&1
if errorlevel 1 set "MSG=Could not save the files (git commit failed)." & goto :die_log
:nocommit

git push -u origin main >>"%LOG%" 2>&1
if not errorlevel 1 goto :pushed
echo   GitHub already has other files. Combining them with yours...
git pull --rebase --allow-unrelated-histories origin main >>"%LOG%" 2>&1
if not errorlevel 1 goto :pullok
git rebase --abort >nul 2>nul
set "MSG=Could not combine with the files already on GitHub. See the log below."
goto :die_log
:pullok
git push -u origin main >>"%LOG%" 2>&1
if errorlevel 1 set "MSG=Push failed. Check that your GitHub account can write to %REPO_SLUG%." & goto :die_log
:pushed

for /f %%i in ('git rev-parse HEAD') do set "SHA=%%i"
echo   Files are on GitHub.

if defined RENDER_DEPLOY_HOOK goto :render_hook
if exist ".render_blueprint_opened" goto :render_auto
echo   Render: opening the one-time setup page. Sign in and press Apply.
start "" "%BLUEPRINT_URL%"
echo opened> ".render_blueprint_opened"
goto :render_done
:render_hook
curl.exe -s -X POST "%RENDER_DEPLOY_HOOK%" >nul 2>nul
echo   Render: deploy requested.
goto :render_done
:render_auto
echo   Render: it deploys automatically from this push.
:render_done

rem ======================================================
echo.
echo [Step 2/3] Building the APK on GitHub. It downloads here when ready...
rem ======================================================
set /a TRY=0
:findrun
set /a TRY+=1
for /f "delims=" %%r in ('gh run list --repo %REPO_SLUG% --workflow %WORKFLOW% --commit %SHA% --limit 1 --json databaseId --jq ".[0].databaseId // empty" 2^>nul') do set "RUNID=%%r"
if defined RUNID goto :havejob
if !TRY! LEQ 6 goto :findwait
for /f "delims=" %%r in ('gh run list --repo %REPO_SLUG% --workflow %WORKFLOW% --branch main --limit 1 --json databaseId --jq ".[0].databaseId // empty" 2^>nul') do set "RUNID=%%r"
if defined RUNID goto :havejob
:findwait
if !TRY! GEQ 36 goto :norun
timeout /t 5 /nobreak >nul
goto :findrun

:norun
echo   GitHub did not start a build. Open %REPO_WEB%/actions and make sure Actions is enabled.
goto :step3

:havejob
echo   Build started. This usually takes 5 to 10 minutes.
set /a TICK=0
:pollloop
set "STATUS="
for /f "delims=" %%s in ('gh run view %RUNID% --repo %REPO_SLUG% --json status --jq ".status" 2^>nul') do set "STATUS=%%s"
if /i "!STATUS!"=="completed" goto :buildended
set /a TICK+=1
set /a MOD=TICK %% 4
if !MOD!==0 (set /a MINS=TICK*15/60 & echo   Still building... about !MINS! minutes so far)
if !TICK! GEQ 160 goto :buildtimeout
timeout /t 15 /nobreak >nul
goto :pollloop

:buildtimeout
echo   The build is taking too long. Check %REPO_WEB%/actions/runs/%RUNID%
goto :step3

:buildended
set "CONC="
for /f "delims=" %%c in ('gh run view %RUNID% --repo %REPO_SLUG% --json conclusion --jq ".conclusion" 2^>nul') do set "CONC=%%c"
if /i not "!CONC!"=="success" goto :buildfailed

if not exist "dist" mkdir "dist"
if exist "dist\BITS-Tandem-Centre.apk" del /q "dist\BITS-Tandem-Centre.apk"
gh run download %RUNID% --repo %REPO_SLUG% --name BITS-Tandem-Centre-apk --dir dist >>"%LOG%" 2>&1
if exist "dist\BITS-Tandem-Centre.apk" goto :apkok
set "RUNNUM="
for /f "delims=" %%n in ('gh run view %RUNID% --repo %REPO_SLUG% --json number --jq ".number" 2^>nul') do set "RUNNUM=%%n"
gh release download build-!RUNNUM! --repo %REPO_SLUG% --pattern "*.apk" --dir dist --clobber >>"%LOG%" 2>&1
if exist "dist\BITS-Tandem-Centre.apk" goto :apkok
echo   The build passed but the APK could not be downloaded. Get it from %REPO_WEB%/releases/latest
goto :step3

:apkok
echo   Build passed. APK saved to: %~dp0dist\BITS-Tandem-Centre.apk
set "OK=1"
goto :step3

:buildfailed
echo   The build FAILED. Fetching the exact error from GitHub...
gh run view %RUNID% --repo %REPO_SLUG% --log-failed > "%ERRLOG%" 2>&1
echo   ---- last lines of the error ----
powershell -NoProfile -Command "Get-Content -LiteralPath '%ERRLOG%' -Tail 40"
echo   ---------------------------------
echo   Full error log saved to: %ERRLOG%
echo   Send that file to Claude and the problem will be fixed.

rem ======================================================
:step3
echo.
echo [Step 3/3] Checking that the Render server is live...
rem ======================================================
set /a TRY=0
:srvloop
set "CODE="
for /f %%c in ('curl.exe -s -o nul -w "%%{http_code}" --max-time 20 "%SERVER_URL%/health"') do set "CODE=%%c"
if "!CODE!"=="200" goto :srvok
set /a TRY+=1
if !TRY! GEQ 18 goto :srvlate
timeout /t 10 /nobreak >nul
goto :srvloop
:srvok
set "SRV=LIVE"
echo   Server is live.
goto :summary
:srvlate
set "SRV=NOT RESPONDING YET"
echo   The server did not answer yet. Finish the Render step if you have not, then open the health link below.

:summary
echo.
echo ====================================================
echo   Live server  : %SERVER_URL%   [!SRV!]
echo   Health check : %SERVER_URL%/health
if "!OK!"=="1" echo   APK on this PC: %~dp0dist\BITS-Tandem-Centre.apk
echo   APK online   : %REPO_WEB%/releases/latest
if defined RUNID echo   Build page   : %REPO_WEB%/actions/runs/%RUNID%
echo ====================================================
if exist "%LOG%" del /q "%LOG%" >nul 2>nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\finish.ps1" -Success !OK! 2>nul
echo.
pause
endlocal
exit /b 0

:die_log
echo.
echo [X] %MSG%
echo   ---- details ----
powershell -NoProfile -Command "Get-Content -LiteralPath '%LOG%' -Tail 25"
echo   Full log: %LOG%
echo.
pause
exit /b 1

:die
echo.
echo [X] %MSG%
echo.
pause
exit /b 1
