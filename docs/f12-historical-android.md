# Envío histórico F12 desde Android

La aplicación principal puede preparar el último día local completamente cerrado con
`UsageEvents`, reconstruir la duración interactiva mediante el calculador F12 v3 y
enviar la representación `daily_use_duration_minutes_v2` a
`POST /api/v1/profiles/{profile_id}/historical-submissions`.

Este flujo es independiente de `POST /usage`: tiene su propio collector, outbox cifrada,
worker y política de errores. Nunca usa `DailyUsage.totalUsageMinutes` como F12.

## Estado de habilitación

- Debug: `-Pf12HistoricalSubmissionsEnabled=true` lo habilita explícitamente; el valor
  predeterminado es `false`.
- Release: permanece forzado a `false` hasta que Analytics apruebe elegibilidad,
  calidad y configuración C1 productiva.
- Demo: no inicia workers reales.

La habilitación exige además modo infantil, perfil activo con onboarding completo,
dispositivo vinculado al mismo perfil, token DeviceBearer y Usage Access. El worker
requiere red y se programa cada 24 horas. Solo prepara el día anterior; no realiza
backfill histórico.

## Identidad, persistencia y reintentos

El `request_id` se deriva de perfil, dispositivo, zona, versión y fecha local. El request
completo queda congelado en una outbox cifrada y se reutiliza tras reinicios. Un recibo
`PROCESSED`, `CONFLICT` o un error permanente impide regenerar ese día.

- Red, 408, 429 y 5xx: WorkManager reintenta el mismo request.
- `TECHNICAL_ERROR` reintentable con `submission_id`: se usa el endpoint `/retry`.
- 401: se pausa la entrada y se invalida la credencial del dispositivo.
- 403: se pausa hasta una autorización de monitoreo explícita.
- 409 y 422 sin recibo histórico válido: fallo permanente, sin crear otro identificador.
- `PENDING` y `SENDING`: se conserva el request para conciliación posterior.

Una corrección de evidencia ya procesada no se reenvía automáticamente. El backend
actual conserva el día como inmutable y responde conflicto ante contenido diferente;
la política de correcciones requiere un contrato posterior de Analytics/backend.

## Contrato fijado

- `measurement_version`: `capture-test-v1`
- `representation_spec_id`: `daily_use_duration_minutes_v2`
- `phenomenon`: `DAILY_USE_DURATION`
- fuente: `android-usage-events`
- puente: `f12-backend-bridge-v1-candidate`
- unidad: `minutes`
- cobertura cuantitativa: `null`

`MISSING` conserva `value=null`; nunca se convierte en cero. `OBSERVED_ZERO` usa cero
explícito. La identidad de serie, asignación e intervalos canónicos se vuelven a derivar
en el backend desde el dispositivo autenticado.

## Pendiente para producción

Matías/Analytics debe aprobar una configuración C1 distinta del perfil smoke no
calibrado, los criterios que hacen elegible un día real, la política de correcciones y
la operación de `PENDING`/`SENDING` interrumpidos. Hasta entonces solo debe habilitarse
en una build debug controlada contra un backend y Analytics de prueba.
