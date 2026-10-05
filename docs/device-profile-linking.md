# Prueba manual Device ↔ Profile

El flujo real está habilitado solamente con `DEMO_MODE=false`. La demo continúa usando el perfil local y no registra Devices en FastAPI.

## Preparación por USB

1. Levantar FastAPI en el puerto `8000` y comprobar `http://127.0.0.1:8000/health`.
2. Conectar el teléfono con depuración USB habilitada.
3. Crear el túnel:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse tcp:8000 tcp:8000
   ```

4. Compilar una APK real para el túnel USB:

   ```powershell
   .\gradlew.bat assembleDebug -PdemoMode=false -PapiBaseUrl=http://127.0.0.1:8000/
   ```

El `adb reverse` debe repetirse después de desconectar o reiniciar el teléfono.

## Caso A — perfil nuevo

1. Borrar los datos de Moderá para comenzar sin DataStore.
2. Elegir “Dispositivo del niño”.
3. Elegir “Crear un perfil nuevo”.
4. Completar el onboarding.
5. Verificar en FastAPI `POST /api/v1/profiles`.
6. Verificar luego `POST /api/v1/profiles/{profile_id}/devices`.
7. Confirmar en DataStore las claves independientes `profile_id`, `device_profile_id`, `device_id` y `device_installation_id`.
8. Cerrar y reabrir la app. No debe solicitar un código.

Si falla solamente el registro del Device, pulsar nuevamente el botón del perfil. El reintento repite únicamente el POST de Device y no crea otro Profile.

## Caso B — recuperación

1. Usar un Profile existente.
2. Generar un código desde Swagger con `POST /api/v1/profiles/{profile_id}/device-link-codes`.
3. Borrar los datos de Moderá o reinstalarla.
4. Elegir “Dispositivo del niño” y luego “Vincular un perfil existente”.
5. Ingresar el código de seis dígitos y vincular.
6. Verificar `POST /api/v1/device-links/claim`.
7. Confirmar que se recuperó el mismo `profile_id`, con nuevos `installation_id` y `device_id` para la instalación.
8. Volver a usar el código consumido: debe mostrar el mensaje de código inválido o vencido.

## Caso C — código vencido

Ingresar un código expirado. La app debe mostrar: “El código no es válido o venció. Pedí uno nuevo e intentá nuevamente.” y conservar la identidad local previa.
