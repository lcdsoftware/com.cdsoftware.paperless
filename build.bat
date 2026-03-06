@echo off

set DEBUG_MODE=

if "%1" == "debug" (
  set DEBUG_MODE=debug
)

cd com.cdsoftware.paperless.targetplatform
call .\plugin-builder.bat %DEBUG_MODE% ..\com.cdsoftware.paperless ..\com.cdsoftware.paperless.test
cd ..
