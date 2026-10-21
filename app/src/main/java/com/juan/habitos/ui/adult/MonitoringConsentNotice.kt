package com.juan.habitos.ui.adult

internal const val MONITORING_ACCEPTANCE_TEXT =
    "Leí la información sobre el monitoreo y autorizo el tratamiento de los datos indicados para las finalidades informadas"

internal data class MonitoringNoticeSection(val title: String, val body: String)

/** Functional notice, not a claim of comprehensive legal compliance or future retention policy. */
internal val monitoringNoticeSummary = listOf(
    MonitoringNoticeSection("¿Qué información registramos?",
        "Tiempo de uso del dispositivo y por aplicación, cantidad y duración de sesiones, horarios de uso incluida la actividad nocturna, e información necesaria del perfil infantil. La disponibilidad depende de los permisos y de la evidencia que devuelve Android. " +
            "El monitoreo no accede al contenido de mensajes, conversaciones, fotografías, contactos ni a la ubicación precisa del dispositivo."),
    MonitoringNoticeSection("¿Para qué utilizamos esta información?",
        "Para elaborar resúmenes de uso, identificar variaciones y brindar información orientada al acompañamiento familiar, de acuerdo con las funcionalidades disponibles. Las métricas del teléfono no prueban quién lo utilizó físicamente."),
    MonitoringNoticeSection("¿Dónde se almacena y quién puede acceder?",
        "Existen registros locales en el teléfono y métricas sincronizadas con el backend de Moderá. El acceso remoto requiere autenticación y las autorizaciones correspondientes para la cuenta y el perfil."),
    MonitoringNoticeSection("¿Puedo desactivar el monitoreo?",
        "Podés revocar este consentimiento desde la aplicación. El backend deja de admitir nuevas métricas cuando el perfil no tiene una autorización de monitoreo vigente. Revocar no elimina automáticamente los registros anteriores."),
)

internal val monitoringNoticeDetails = listOf(
    MonitoringNoticeSection("Datos recopilados",
        "Se consultan estadísticas y eventos de uso de Android para obtener tiempos por aplicación, sesiones y sus duraciones, horarios y actividad nocturna cuando hay evidencia suficiente. El uso de aplicaciones no equivale necesariamente al tiempo exacto de pantalla interactiva. " +
            "Las métricas pueden incluir identificadores de perfil y dispositivo, paquetes de aplicaciones, fechas y zona horaria. El perfil contiene datos proporcionados como nombre, edad, avatar y horarios; las preferencias o necesidades se registran cuando se completan. " +
            "El monitoreo no lee contenido de mensajes, conversaciones, fotografías, contactos ni ubicación precisa."),
    MonitoringNoticeSection("Finalidades del tratamiento",
        "Los datos permiten consultar resúmenes, revisar cambios de uso y ofrecer información para el acompañamiento familiar según las funcionalidades disponibles. No identifican por sí solos a la persona que usó el teléfono ni demuestran la realización de una actividad."),
    MonitoringNoticeSection("Almacenamiento local y remoto",
        "Android conserva su propia evidencia de uso. Moderá mantiene configuración y registros locales, incluidas instantáneas pendientes de sincronización. El flujo normal envía métricas agregadas al backend, no el contenido de las aplicaciones ni los eventos originales de diagnóstico. " +
            "Las herramientas de diagnóstico debug pueden generar y compartir archivos con eventos originales mediante una acción separada y explícita; esta autorización no comparte esos archivos automáticamente."),
    MonitoringNoticeSection("Controles de acceso",
        "Las operaciones remotas usan credenciales y controles de cuenta, perfil y dispositivo. La autorización de monitoreo requiere un adulto vinculado, verificación del representante aprobada y consentimiento vigente. Iniciar sesión por correo no reemplaza esa verificación. " +
            "Los permisos de uso de Android son necesarios para capturar las métricas y se administran por separado."),
    MonitoringNoticeSection("Revocación del consentimiento",
        "Podés revocar esta autorización desde la aplicación, con confirmación. El backend rechaza nuevas métricas si el perfil queda sin autorización vigente; otro adulto vinculado podría mantener una autorización válida. " +
            "No se eliminan automáticamente datos históricos, instantáneas locales ni la evidencia de uso que conserva Android. Revocar tampoco retira por sí solo los permisos de Android."),
    MonitoringNoticeSection("Conservación de los registros",
        "La revocación no inicia un borrado automático de los registros anteriores. Este MVP no ofrece una garantía de eliminación al cumplirse un plazo desde esta pantalla. " +
            "Deben definirse y aplicarse los períodos de conservación de métricas, historiales, registros temporales y copias antes de utilizarlos con familias reales."),
    MonitoringNoticeSection("Acceso, rectificación y supresión",
        "La aplicación permite consultar la información disponible en el panel adulto y editar los datos del perfil que tienen esa opción. No ofrece un trámite integral para ejercer los derechos de acceso, rectificación y supresión ni una función para borrar todos los datos. " +
            "Para uso con familias reales falta definir el responsable del tratamiento, un canal de solicitudes y el procedimiento de respuesta. Esta información funcional del MVP no constituye un aviso legal integral."),
)
