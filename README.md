# Hábitos — frontend Android

Aplicación Android experimental para una tesis sobre hábitos de uso del celular en niños y niñas.

## Usage Diagnostics

Las compilaciones `debug` incluyen una segunda entrada en el launcher llamada **Diagnóstico de uso**. Al abrirla, se debe conceder **Acceso a estadísticas de uso** desde la configuración de Android y regresar a la pantalla. Permite consultar los últimos 30 minutos, el día actual o las últimas 24 horas, comparar `UsageStats` agregados con `UsageEvents` y copiar el resultado como texto.

Esta función es exclusivamente experimental. Sus métricas todavía no forman parte del contrato del backend ni alimentan las pantallas normales. La disponibilidad de eventos como `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE` y `KEYGUARD_HIDDEN` puede variar según la versión de Android y el fabricante. Las sesiones y los desbloqueos mostrados son estimaciones y deben validarse mediante pruebas controladas en dispositivos reales.

## Integración de Profile en desarrollo

La compilación debug usa el perfil demo local por defecto (`demoMode=true`) y no depende de FastAPI. Para probar el flujo real de creación de Profile contra un backend disponible en `http://10.0.2.2:8000/`, ejecutar desde Windows:

```powershell
.\gradlew.bat installDebug -PdemoMode=false
```

Con `demoMode=false`, el onboarding envía los datos seleccionados a `POST /api/v1/profiles` y guarda en DataStore la respuesta remota, incluido el ID. El repository también expone operaciones puntuales de refresh (`GET`) y update (`PUT`); no hay polling ni sincronización en segundo plano.
