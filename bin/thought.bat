@echo off
set SCRIPT_DIR=%~dp0
set JAR_PATH=%SCRIPT_DIR%..\target\thoughtcoding.jar

REM Check if Java is available
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Java is not installed or not in PATH
    exit /b 1
)

REM Check if JAR file exists
if not exist "%JAR_PATH%" (
    echo ❌ JAR file not found: %JAR_PATH%
    echo Please build the project first: mvn clean package
    exit /b 1
)

REM Run the application
java -jar "%JAR_PATH%" %*