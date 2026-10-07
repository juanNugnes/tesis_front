# Prueba manual: Adult authentication y AdultProfileLink

Usar `DEMO_MODE=false`. Para un teléfono físico conectado por USB, compilar apuntando al backend reenviado por ADB:

```powershell
$env:JAVA_HOME='C:\Users\Juan\.jdks\jbr-21.0.11'
.\gradlew.bat assembleDebug -PdemoMode=false -PapiBaseUrl=http://127.0.0.1:8000/
$adbTool=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adbTool reverse tcp:8000 tcp:8000
& $adbTool install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

## A. Dispositivo infantil

1. Ingresar con un Profile existente y Device JWT válido.
2. Abrir el panel adulto local con el PIN y entrar al perfil.
3. Elegir **Vincular a un adulto responsable**.
4. Mantener visible o anotar el código `ADULT_PROFILE_LINK` y su vencimiento.

## B. Dispositivo o flujo adulto

1. En una instalación separada, elegir **Dispositivo del adulto**.
2. Ingresar el email y solicitar el OTP.
3. Ingresar el OTP; en builds debug puede verse `dev_code` identificado como DEBUG.
4. Verificar que la lista de `/adult/profiles` esté vacía si la cuenta aún no tiene perfiles.
5. Ingresar el código generado por el dispositivo infantil.
6. Confirmar que el Profile aparece y entrar a su panel adulto.

## C. Reinicio

1. Cerrar y volver a abrir la app adulta.
2. Confirmar que `/auth/adult/me` valida el Adult JWT.
3. Confirmar que `/adult/profiles` conserva el Profile vinculado y permite abrirlo.

Un `401` debe volver al login adulto sin borrar la identidad ni el JWT Device del flujo infantil. Un `403` no debe borrar el Adult JWT.
