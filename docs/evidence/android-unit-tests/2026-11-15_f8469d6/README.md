# Evidencia de pruebas unitarias Android (debug)

- Fecha y hora local de ejecución: 2026-11-15 21:14–21:15 (UTC-03:00, America/Buenos_Aires)
- Tarea ejecutada: `.\gradlew.bat :app:testDebugUnitTest --console=plain`
- Commit HEAD: `f8469d6262d77b5a12d5d827a8e826d204a8b845`
- Rama: `test_activity_design`
- Estado del árbol: con modificaciones locales previas a la ejecución, incluidas pruebas modificadas y pruebas nuevas sin seguimiento
- JDK usada: JetBrains Runtime 21.0.11 (`C:\Users\Juan\.jdks\jbr-21.0.11`)

## Resultado

| Métrica | Cantidad |
|---|---:|
| Ejecutadas | 466 |
| Satisfactorias | 465 |
| Fallidas | 1 |
| Omitidas | 0 |
| Con errores | 0 |

La única falla corresponde a `com.juan.habitos.data.device.InteractiveScreenF12V3ParityTest`, método `real Kotlin calculator matches all shared F12 v3 vectors and exports comparator results`. La prueba terminó en `InteractiveScreenF12V3ParityTest.kt:19` porque no encontró el archivo externo `C:\Users\Juan\Projects\modera-modelo\tests\fixtures\interactive_screen_vectors_v3.json`. Se clasifica como falla de entorno/datos de prueba ausentes: la prueba se interrumpe antes de ejecutar las aserciones sobre el código productivo.

El primer intento no inició pruebas porque el `JAVA_HOME` heredado apuntaba a `C:\Users\LENOVO\.jdks\jbr-17.0.11`, una ruta inexistente. La ejecución informada arriba se realizó después con la JDK válida indicada, sin modificar código ni pruebas.

Los archivos de `html/` son una copia completa del reporte Gradle. Los 53 archivos de `xml/` son los resultados JUnit originales generados por la tarea.
