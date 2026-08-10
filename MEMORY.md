# vEnderchest — memoria de desarrollo

## Rol

Componente de máxima criticidad por almacenar ítems y por su historial de posibles
duplicaciones. Es el primer plugin elegido para la modernización del stack.

## Base actual

- Versión del plugin: 1.0.2.
- `1.0.0` es el baseline SemVer reiniciado: PATCH para correcciones compatibles, MINOR
  para funciones compatibles y MAJOR para cambios incompatibles.
- Java 21 y Paper API 1.21.11.
- Gradle Kotlin DSL con Shadow.
- SQLite, MySQL, H2 y HikariCP.
- Sesiones autoritativas, revisiones CAS, auditoría y API pública inmutable.
- Doce clases de pruebas sobre revisiones, diffs, sesiones, caché, configuración, auditoría, mensajes, backups y API.
- Produce un jar de runtime y un jar separado para la API pública.
- Gradle Wrapper 9.1.0 completo.
- Shadow 9.3.0.
- Estado verificado: 47 tests, 0 fallos y build completo correcto.
- `MessageService` común con Components, MiniMessage, estilos y emojis configurables.
- `/venderchest help|about|reload` implementado con líneas vacías, hover y click.
- La salida estructurada `[audit]` de consola se controla con `audit.console-enabled`
  (default `true`) y respeta reload; no altera protección, persistencia ni eventos API.
- Un conflicto CAS revierte el balance neto vault/inventario antes de permitir navegar;
  esto evita que un retiro rechazado permanezca en la página original y pueda
  depositarse en otra.
- Las acciones de soltar objetos desde una sesión abierta se bloquean hasta confirmar
  el commit, para que la reversión no tenga que perseguir entidades en el mundo.
- Causa del incidente de reaparición: una lectura asíncrona antigua podía terminar después
  de un commit más nuevo y publicar su revisión anterior en `contentCache`. Una reapertura
  servía ese snapshot viejo y permitía retirar o consumir ítems antes de que el CAS detectara
  el conflicto al cerrar.
- Corrección: todas las publicaciones de lecturas y commits pasan por `cacheLatest`; la caché
  solo cambia cuando la revisión candidata es estrictamente mayor. Una revisión menor o igual
  nunca reemplaza el estado ya publicado.
- Regresión cubierta por `GuiManagerCacheTest`: publicar rev 28, completar después rev 27 y
  reabrir conserva rev 28; una llegada tardía de la misma revisión tampoco reemplaza el estado.
- Hardening UniverseSpigot: los comandos pueden ejecutarse en `universe-command-thread`; antes,
  un cache hit podía alcanzar `createInventory`/`openInventory` fuera del hilo principal. Todas
  las entradas de apertura y las completions asíncronas pasan ahora por un único dispatch que se
  ejecuta inline en main y se agenda exactamente una vez fuera de main. La vigencia del jugador y
  de la sesión se comprueba después del dispatch. `GuiManagerMainThreadTest` cubre cache hit,
  completion asíncrona y ejecución inline. Esto corrige un bug real de thread-safety, pero no
  demuestra por sí solo que fuese la causa concreta del incidente de bruunnf.
- Causa adicional demostrada en 1.0.2: el registro por `(owner, vault)` no ordenaba aperturas del
  mismo actor hacia páginas distintas. Una completion antigua podía publicar su GUI después de una
  solicitud nueva y reemplazar `openByPlayer` antes de que `openInventory` cerrara la vista previa;
  el cierre de esa vista quedaba sin commit. Ahora cada solicitud lleva una secuencia por actor,
  solo la más reciente puede publicar y el mapping nuevo se instala después de abrir la vista.
- Paper prohíbe abrir o cerrar inventarios dentro de `InventoryClickEvent`; navegación, home,
  cierre y backups se ejecutan al tick siguiente y revalidan la sesión. Los snapshots capturados
  clonan cada `ItemStack`, evitando que una referencia viva modifique la línea base de un commit.
- `GuiManagerMainThreadTest` reproduce el orden página 3 solicitada primero / página 2 solicitada
  después: aunque termine primero la nueva, la completion vieja se descarta y su sesión se cierra.
- Los YAML existentes se fusionan al cargar con los recursos embebidos: solo se copian rutas
  hoja ausentes, sin reemplazar escalares, listas vacías, secciones personalizadas ni claves
  desconocidas. Se guarda únicamente cuando aparecen defaults nuevos y la segunda carga es
  idempotente. La lectura con `parseComments(true)` conserva comentarios existentes, aunque
  Bukkit puede normalizar el formato del YAML durante la primera escritura con novedades.
- `ConfigManagerMergeTest` verifica la actualización desde disco, listas nuevas y existentes,
  valores personalizados, comentarios y ausencia de reescritura en la segunda carga.

## Invariantes obligatorias

- Un vault tiene como máximo un escritor autorizado.
- Un commit compara la revisión esperada y nunca sobrescribe un estado más nuevo.
- La caché de un vault nunca retrocede de revisión ni reemplaza una revisión igual ya publicada.
- Toda creación, publicación o apertura de inventarios Bukkit converge en el hilo principal;
  las rutas de comando de UniverseSpigot y los resultados asíncronos se despachan una sola vez,
  mientras los listeners síncronos se ejecutan inline.
- Para un actor, una completion de apertura solo puede publicarse si sigue siendo su solicitud
  más reciente; la sesión anterior permanece rastreable hasta que Bukkit termina de cerrar su GUI.
- Ningún listener cambia la vista abierta dentro de `InventoryClickEvent`; se agenda al tick siguiente.
- Una actualización agrega defaults YAML ausentes sin sobrescribir configuración existente.
- Cerrar, desconectar, recargar o fallar no puede duplicar ni descartar ítems.
- Reintentar una operación produce el mismo resultado, no una segunda entrega.
- Backups y vistas administrativas no abren una vía de escritura accidental.
- Todo dupe corregido añade una prueba de regresión.

## Bloqueo conocido

Dos servidores que comparten MySQL todavía no deben abrir el mismo vault en escritura
simultánea. Para habilitarlo se necesita un lease distribuido con vencimiento,
fencing token y pruebas de partición/reconexión. Un lock Redis simple no basta.

## Próximo trabajo

1. Migrar los mensajes hardcodeados restantes de `EcAdminCommand`.
2. Añadir paginación reutilizable al renderer de help cuando supere ocho entradas.
3. Ejecutar y ampliar la suite adversarial anti-dupe.
4. Auditar todas las transiciones de sesión y rutas de cierre.
5. Preparar un servidor Paper local aislado.
6. Mantener Paper 1.21.11 como API mínima y bytecode Java 21.
7. Validar también la última versión estable de Paper con su Java requerida.
8. Ejecutar smoke tests de apertura, conflicto, desconexión, reload y apagado.

## Documentos relacionados

- `docs/DUPLICATION_FIX.md`
- `docs/VANTIDUPE_API.md`
- `docs/MIGRATION_REVISION.md`
