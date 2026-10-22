# Preferencias reales y hábitos de pantalla simulados

Onboarding y panel reutilizan `ChildPreferencesScreen`, con las mismas preguntas,
opciones y sección `ParentalScreenTimeSection`. Los hábitos son una demostración
interactiva sin persistencia: sólo viven en `ChildPreferencesUiState.simulatedScreenHabits`.
Se conservan mientras existe ese ViewModel (incluida rotación); no se restauran al
reabrir un formulario nuevo ni al reiniciar el proceso. Cambiar de perfil los limpia.

No se construye `ParentalScreenTimeViewModel` ni se accede al repositorio PMUM desde
estos formularios. No hay GET latest, POST responses, puntuaciones ni inferencia.
La simulación no marca cambios pendientes, no exige AdultBearer y no afecta recomendaciones.
Un único botón guarda exclusivamente `ChildPreferences` con el contrato existente;
el onboarding conserva su continuación y el panel guarda sólo preferencias modificadas.
La autenticación requerida por las preferencias reales se mantiene sin cambios.

## Integración experimental conservada, desconectada del formulario

Se mantienen repositorio, DTO, métodos Retrofit, ViewModel remoto y pruebas anteriores
como código inactivo desde estos formularios. Las notas siguientes describen ese
contrato remoto previo, no el comportamiento actual de la demostración.

## Codificación verificada

CHGender del niño: masculino=1 (Fiú), femenino=2 (Lány).
Evidencia consultada, en la entrega local de Analytics
`modera-analytics-zip/MODERA_ANALYTICS_INTEGRACION_04f1e3a`:

- `docs/model_closure/PMUM_SOURCE_AUDIT_BLOCK2A_2026-10-12.md`, sección Codificación y formulario: cotejo SEM.dat/SEM.sav y etiquetas originales.
- `docs/model_closure/PMUM_C1_CONTRACT_2026-10-12.md`: contrato del candidato HU v1 y dominios.
- `docs/own_closure/evidence/pmum_2026-10-14/PROVENANCE_AUDIT.json`: auditoría del diccionario fuente.

Tiempo: 1=<1 h; 2=1–2 h; 3=3–4 h; 4=5–6 h; 5=7 h o más.
Se conservan los huecos; no se convierten en minutos ni se usa UsageStats.
La redacción weekday solicitada para Moderá no prueba equivalencia textual con
el cuestionario fuente: su etiqueta SAV está truncada, según la auditoría.

## Límites del contrato existente

- `null` no distingue omisión de «No sé / Prefiero no responder». Al recuperar
  se muestra sin selección; no se inventa una sexta categoría ni se usa código 4.
- Cada POST crea una revisión. No hay request_id/idempotencia contractual.
  Android evita dobles pulsaciones, reenvíos sin cambios y reintentos automáticos
  de conexión; después de un POST fallido exige consultar lo almacenado antes
  de permitir un nuevo envío explícito. No garantiza idempotencia global ante
  concurrencia entre dispositivos, reinicio del proceso o todas las carreras de red.
- El backend confirma primero la respuesta y luego registra la estimación en
  otra transacción. La pérdida de una respuesta HTTP no demuestra que no se guardó.
- GET latest es por perfil, no por autor de la respuesta. Los adultos autorizados
  pueden recuperar la última revisión del perfil.
- La API admite enteros fuera del dominio del modelo. Android solo envía códigos
  admitidos y no sobrescribe silenciosamente revisiones con formato no reconocido.
- PMUM deshabilitado o un resultado técnico de inferencia no invalida una respuesta
  persistida dentro de una respuesta HTTP exitosa.

Posibles ajustes futuros para tesis_back, no implementados aquí: idempotencia y
reconciliación por request_id, distinción persistida de omitido/no responde,
validación explícita de códigos y versión de formulario; revisar la separación
de confirmación del guardado respecto de la inferencia y el alcance de latest.
