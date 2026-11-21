# Evidencia original F12 en Android

## Contrato verificado

La implementación se contrastó con `analytics_service/f12_export.py`, sus pruebas y el productor de referencia
`tools/android-diagnostic/.../DiagnosticExport.kt` de `modera-modelo`. Cada consulta produce exactamente dos
secuencias de bytes UTF-8:

- un resumen JSON con los nueve campos aceptados por Analytics;
- un JSONL con los catorce campos de `raw-usage-event-v1`, una línea por evento.

Perfil, dispositivo, fecha local, zona horaria, revisión e indicadores de calidad no se agregan a esos archivos.
Se conservan en el sobre local cifrado. El hash SHA-256 se calcula sobre los bytes JSON y JSONL que luego se
archivan, sin volver a serializarlos al reintentar.

La captura utiliza `AndroidDeviceUsageDataSource.collectRun`, el mismo acceso a `UsageEvents` ya existente. No
duplica la consulta ni modifica la captura agregada o la sincronización `/usage`.

## Conservación local

`EncryptedF12RawEvidenceStore` guarda cada sobre bajo `noBackupFilesDir/f12_raw_evidence_v1`. El contenido
completo se cifra con AES-GCM y una clave no exportable de Android Keystore. El nombre del archivo es un
identificador SHA-256 y no expone perfil, dispositivo ni fecha.

- Una repetición con la misma fecha, revisión y bytes es idempotente.
- La misma revisión con bytes diferentes se rechaza.
- Una corrección exige una revisión estrictamente mayor y conserva la anterior mientras esté dentro de la
  retención.
- Se conservan como máximo 16 sobres y 96 MiB de bytes fuente. Cada sobre respeta además el límite de 32 MiB
  de Analytics. Toda expulsión se devuelve explícitamente en `evictedCaptureIds`.
- No se almacenan payloads HTTP, tokens ni campos ajenos al contrato. `packageName` y `className` se conservan
  porque forman parte del contrato estricto; no se recolectan datos adicionales del evento.

Los estados locales nunca afirman cobertura diaria completa. Se registran solamente éxito/fallo de consultas,
cantidad de eventos, existencia de estado previo observado y presencia de eventos de reinicio. Incluso una
captura con eventos se marca `CAPTURE_OBSERVED_UNVERIFIED` y lleva `DAY_COMPLETENESS_NOT_ASSERTED`.

## Activación y aislamiento

La entrada local `F12RawEvidenceCaptureCoordinator.captureClosedDay` sólo acepta días cerrados. No tiene cliente
HTTP, no está registrada en WorkManager y no es invocada desde `Application.onCreate`.

La flag `F12_RAW_EVIDENCE_CAPTURE_ENABLED` permanece desactivada por defecto en debug y forzada a `false` en
release. Para una futura captura manual de desarrollo puede compilarse debug con
`-Pf12RawEvidenceCaptureEnabled=true`; esto no habilita el envío histórico existente ni procesamiento automático.

## Validación pendiente

El endpoint receptor ya está integrado. Sigue pendiente habilitar en el backend el paso separado que tome los
bytes conservados y los entregue a Analytics; Android no inicia C1 ni crea `historical-submissions` desde este flujo.

También queda pendiente una prueba instrumentada en dispositivo del cifrado Android Keystore, recuperación tras
reinicio del proceso y retención con archivos reales. Las reglas de idempotencia, revisión, formato y retención se
prueban como unidades JVM; el cifrado depende del runtime Android y no se simula como prueba unitaria.

El contrato no representa un fallo ocurrido al consultar el propio estado de permiso (antes de `queryEvents`) con
`usageAccessAvailable=false` y un código de excepción. Si Android llega a ese estado excepcional, el validador
local detiene el archivado en vez de producir un par que Analytics rechazaría. Los estados representables
(`USAGE_ACCESS_NOT_GRANTED`, `QUERY_RETURNED_NULL`, `SECURITY_EXCEPTION` y `QUERY_RUNTIME_EXCEPTION`) sí se
conservan como evidencia, incluso sin eventos.

## Transporte al backend

`F12EvidenceTransportCoordinator` implementa el envío explícito a
`POST /api/v1/profiles/{profile_id}/f12-evidence/v1`. Utiliza la autorización de dispositivo existente y no posee
un token propio. El cliente OkHttp tiene desactivado el reintento automático: cada nuevo intento es decidido por el
coordinador y vuelve a leer el mismo registro cifrado.

El recorrido es:

1. `EncryptedF12RawEvidenceStore` descifra el sobre y verifica nuevamente hashes y `capture_id`.
2. Se serializa solamente el manifest. Los arrays `summaryBytes` y `eventsBytes` se agregan directamente como
   `RequestBody`; no se interpretan ni reserializan.
3. El multipart agrega `manifest` como campo de texto y luego cada `summary`/`events` en el orden de `pairs`.
4. Se calcula `contentLength()` antes de enviar y se rechaza localmente un body desconocido o mayor a 48 MiB.
5. Sólo una respuesta 200/201 con recibo consistente marca `DELIVERED`. El recibo completo se guarda dentro del
   sobre cifrado. Un timeout conserva `RETRY_PENDING`; 401/403, 409 y fallos permanentes quedan diferenciados.

`UNAVAILABLE/NOT_EXPORTED` usa el marcador SHA-256 definido por backend y se envía sin partes `summary` ni
`events`. No se genera a partir de un día observado con cero eventos.

Las flags `F12_RAW_EVIDENCE_CAPTURE_ENABLED` y `F12_RAW_EVIDENCE_TRANSPORT_ENABLED` son `false` por defecto.
Release fuerza ambas a `false`; no existe worker ni planificación periódica para este transporte.

## Prueba manual debug

Requisitos: dispositivo vinculado, token de dispositivo vigente, autorización `USAGE_MONITORING`, Usage Access y
un día local ya cerrado. La APK de desarrollo se compila explícitamente:

```powershell
.\gradlew.bat assembleDebug `
  -PdemoMode=false `
  -PapiBaseUrl=https://modera-api.duckdns.org/ `
  -Pf12RawEvidenceCaptureEnabled=true `
  -Pf12RawEvidenceTransportEnabled=true
```

Después de instalarla, el receptor existe únicamente en debug y exige el permiso de sistema `android.permission.DUMP`,
por lo que se invoca desde ADB shell:

```powershell
adb shell am broadcast `
  -n com.juan.habitos/com.juan.habitos.diagnostics.F12EvidenceDebugReceiver `
  -a com.juan.habitos.debug.SEND_F12_EVIDENCE `
  --es local_date 2026-11-18 `
  --ei revision 1

adb logcat -s F12EvidenceDebug:I
```

El log sólo informa estado, `capture_id`, `receipt_id`, revisión y código de error; nunca token, manifest ni eventos.
Repetir el comando con igual fecha y revisión reutiliza la evidencia guardada. Una corrección real debe usar una
revisión mayor. Esta prueba sólo valida recepción y persistencia: no activa `historical-submissions`, Analytics C1
ni procesamiento automático.
