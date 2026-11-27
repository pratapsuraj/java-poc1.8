@echo off
rem One-time setup of the developer (debug) environment on Windows. Run it once from a Command Prompt or by double-click.
rem   Docker: HBase + Spark master + 2 workers.   On your machine: Java API, Scala job, Angular UI (debug in VS Code).
cd /d "%~dp0"

echo [1/6] Checking tools...
if not defined JAVA_HOME (
  echo JAVA_HOME is not set. Install JDK 8 ^(Temurin 8^) and set JAVA_HOME to its folder, then run this again.
  goto fail
)
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /c:"1.8." >nul
if errorlevel 1 (
  echo JAVA_HOME ^(%JAVA_HOME%^) is not JDK 8. This project needs Java 8.
  goto fail
)
where mvn >nul 2>nul || ( echo Maven was not found on PATH. Install Maven 3.6+ . & goto fail )
where node >nul 2>nul || ( echo Node.js was not found on PATH. Install Node 20.19 or newer. & goto fail )
where docker >nul 2>nul || ( echo Docker was not found. Install Docker Desktop or Rancher Desktop. & goto fail )
docker info >nul 2>nul || ( echo Docker is not running. Start it, wait until ready, then run this again. & goto fail )

echo [2/6] Building the Spark image ^(first time downloads Spark, a few minutes^)...
docker build -t salesbatch-runtime docker || goto fail

echo [3/6] Building the Java and Scala code...
call mvn -B -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/local-classpath.txt
if errorlevel 1 goto fail

echo [4/6] Installing the Angular dependencies...
pushd ui
call npm ci
if errorlevel 1 ( popd & goto fail )
popd

echo [5/6] Writing .vscode\settings.json for this machine...
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\write-vscode-settings.ps1

echo [6/6] Starting HBase and the Spark cluster in Docker...
docker compose -f docker/local/docker-compose.yml up -d || goto fail

ping -n 1 host.docker.internal >nul 2>nul
if errorlevel 1 echo NOTE: host.docker.internal does not resolve on this machine. Add the line "127.0.0.1 host.docker.internal" to C:\Windows\System32\drivers\etc\hosts ^(as administrator^).

echo.
echo Setup done. HBase needs about a minute to be ready.
echo Next: open this folder in VS Code ^(start VS Code from a new terminal so it sees JAVA_HOME^), install the
echo extensions "Extension Pack for Java" and "Scala (Metals)", accept the Metals import, then run
echo     node scripts\fix-bloop.js     and   Metals: Restart build server
echo Then pick a launch config in Run and Debug. See README "Debug in the IDE".
echo UI: cd ui, npm start, open http://localhost:4300
pause
exit /b 0

:fail
echo.
echo Setup stopped. Read the message above, fix it and run setup-local.cmd again.
pause
exit /b 1
