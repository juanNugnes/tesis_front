# Mensajes para acompañarte (Android MVP)

El Resumen del panel adulto consulta exclusivamente GET
`/api/v1/profiles/{profile_id}/adult-messages/history` del backend configurado.
Se utiliza `AdultRequestAuthorization`: no se utiliza la credencial del dispositivo.
No se solicita ninguna evaluación a Analytics. La consulta se realiza al seleccionar
un perfil y manualmente con «Actualizar mensajes» o «Reintentar».

La respuesta conserva todos los campos del esquema backend `adult_message.py`,
incluidas estadísticas nullable, configuración, identidad, fechas y correcciones.
El historial completo permanece en memoria mientras se muestra el perfil, sin
persistencia local adicional. La UI agrupa por `card_id` y elige el mayor `version`
antes de filtrar estados. Una actualización no se cuenta como otro mensaje.

ACTIVE, INCONCLUSIVE, INSUFFICIENT_DATA y CONTEXT_REVIEW aparecen en seguimiento.
RESOLVED, EXPIRED, SERIES_CHANGED, WITHDRAWN y CORRECTED_HISTORY se consultan con
«Ver historial», cada uno con su estado. Un estado desconocido se muestra en el
historial como no disponible; nunca se considera vigente por defecto. Las versiones
anteriores se conservan en el DTO, pero no se presentan como tarjetas independientes.
Se muestran los textos y recomendaciones originales. `updated_date` prevalece
sobre `evaluation_date`; los días de corrección no se convierten en otra evaluación.
No se modifica `CANDIDATE_NOT_DELIVERED` ni se registra un ACK inexistente.

Una solicitud nueva limpia la información anterior. `collectLatest` cancela las
consultas obsoletas; la navegación comprueba además el identificador del perfil
antes de mostrar el estado. Un perfil nulo limpia la sección. La consulta de
mensajes es independiente de las métricas, por lo que sus errores no se confunden.
401 reutiliza el borrado de sesión adulta y ofrece el inicio de sesión existente.
403 informa falta de autorización. Los errores de servicio conservan la política
`detail.retryable`; no se convierten en un historial vacío. La UI no hace retries
automáticos. Demo usa un repositorio vacío independiente e indica demostración.

## Prueba física por USB

La APK de validación se compila con `-PdemoMode=false`
y `-PapiBaseUrl=http://127.0.0.1:8000/`.

1. Mantener el backend accesible en el puerto 8000 del equipo.
2. Conectar el teléfono con depuración USB y ejecutar `adb reverse tcp:8000 tcp:8000`.
3. Instalar `app/build/outputs/apk/debug/app-debug.apk`.
4. Ingresar con la cuenta adulta existente y elegir un perfil autorizado.
5. Abrir Resumen y buscar «Mensajes para acompañarte».
6. Si el historial está vacío, comprobar «Todavía no hay mensajes disponibles».
7. Si existen mensajes persistidos, comprobar texto, consejo, fecha y estados;
   consultar tarjetas cerradas/corregidas mediante «Ver historial».
8. Cambiar de perfil; nunca deben quedar visibles mensajes del perfil anterior.
9. Desconectar la red o el túnel y actualizar; comprobar error y reintento.

El túnel USB debe restablecerse tras desconectar el teléfono. Para usar Wi-Fi,
compilar con `-PapiBaseUrl=http://<IP-del-equipo>:8000/` accesible desde esa red.
La URL de Analytics nunca se configura en Android.

## Pruebas y límites

`AdultMessagesTest` utiliza una copia exclusivamente de pruebas del ejemplo oficial
ficticio incluido en la entrega Analytics 04f1e3a y en las fixtures del backend.
No se envían ni siembran tarjetas ficticias en servicios reales.
`AdultMessagesViewModelTest` cubre cambios de perfil, consultas canceladas, retry,
estados de sesión y política de errores del servicio.

La integración es de consulta: no garantiza que haya evaluaciones reales ni crea
mensajes automáticamente. El historial vacío no acredita ausencia de cambios.
El candidato Analytics no está validado con familias. No se muestran sus puntajes
experimentales ni se agregan diagnósticos. No hay push, ACK, consulta offline o
generación semanal. Queda pendiente la aceptación visual en un teléfono físico
con mensajes reales ya persistidos.

Validación de esta entrega:

- 13 pruebas nuevas aprobadas (8 de repositorio/modelos y 5 de ViewModel).
- Suite completa: 323 ejecutadas, 322 aprobadas. Falló únicamente la prueba
  preexistente `InteractiveScreenF12V3ParityTest`: falta el archivo externo
  `C:/Users/Juan/Projects/modera-modelo/tests/fixtures/interactive_screen_vectors_v3.json`.
- No se modificó ni omitió esa prueba. JaCoCo se generó después con los datos de
  la corrida completa (`jacocoDebugReport -x testDebugUnitTest`).
- Cobertura global: líneas 56,52 %; ramas 16,12 %; instrucciones 27,16 %.
- Líneas de `AdultMessagesViewModel.kt`: 98,15 %; `AdultMessages.kt`: 68,97 %.
  Estas cifras no equivalen a cobertura de Compose ni a validación física.
- APK debug real compilada con `demoMode=false` y URL `http://127.0.0.1:8000/`.
- Reportes: `app/build/reports/tests/testDebugUnitTest/index.html` y
  `app/build/reports/jacoco/jacocoDebugReport/html/index.html`.
