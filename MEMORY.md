# vEnderchest — memoria de desarrollo

## Rol

Componente de máxima criticidad por almacenar ítems y por su historial de posibles
duplicaciones. Es el primer plugin elegido para la modernización del stack.

## Base actual

- Versión del plugin: 1.1.0.
- `1.0.0` es el baseline SemVer reiniciado: PATCH para correcciones compatibles, MINOR
  para funciones compatibles y MAJOR para cambios incompatibles.
- Java 21 y Paper API 1.21.11.
- Gradle Kotlin DSL con Shadow.
- SQLite, MySQL, Redis/Lettuce, H2 y HikariCP.
- Sesiones autoritativas, revisiones CAS, auditoría y API pública inmutable.
- Doce clases de pruebas sobre revisiones, diffs, sesiones, caché, configuración, auditoría, mensajes, backups y API.
- Produce un jar de runtime y un jar separado para la API pública.
- Gradle Wrapper 9.1.0 completo.
- Shadow 9.3.0.
- Estado verificado de 1.1.0: 123 tests en 25 suites, 0 fallos.
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
- `messages.yml` migra únicamente el prefijo default histórico exacto al canónico; cualquier
  prefijo personalizado permanece intacto y una segunda carga no reescribe el archivo.
- `ConfigManagerMergeTest` verifica la actualización desde disco, listas nuevas y existentes,
  valores personalizados, comentarios y ausencia de reescritura en la segunda carga.
- El modo cross-server es opt-in: MySQL conserva contenido, revisiones, fencing, leases
  durables y journal; Redis solo coordina leases rápidos con token/TTL/Lua. Un writer stale
  nunca puede confirmar contra un fence nuevo y la pérdida de frescura falla cerrada.
- Decisión explícita de Martín (2026-08-11): CROSS usa movimientos Bukkit vanilla inmediatos y
  captura el vault al tick siguiente. Un solo commit CAS fenced queda in-flight por sesión y el
  lease se conserva hasta cerrar/terminar el último commit. Se retiró la emulación por gesto por su
  latencia y complejidad. La ventana aceptada es concreta: kill/crash antes de que MySQL y playerdata
  queden ambos durables puede perder o duplicar el último movimiento; lease/fence evita dos writers,
  pero no vuelve atómicos esos dos almacenes.
- Paper 1.21.11 guarda `PlayerInventory` mediante `Player#saveData`, pero no el `carried`
  de `AbstractContainerMenu`; por eso el cursor CROSS es solo una proyección taggeada de un
  `CursorEscrow` autoritativo en MySQL y nunca habilita ACK. Sus PDC privados contienen
  `escrow_id` + `op_sequence`; toda transición que cambia la proyección incrementa la secuencia.
  Left/right, shift, hotbar/offhand, drag simple/even mixto y double/collect se cancelan y emulan
  contra el ítem canónico. DROP/outside/creative permanecen bloqueados.
- `SETTLEMENT_PREPARED` escribe old/next escrow y BEFORE/AFTER antes de tocar playerdata;
  `VAULT_APPLIED` es solo un stage del mismo payload que prueba que el CAS ya ocurrió y evita
  repetirlo en recovery. Player slots se revalidan juntos, se aplica AFTER y `saveData` precede
  al CAS/finalización. Si queda cursor, se publica otro CURSOR_STABLE; solo consumo total termina
  ACK. Recovery v1 conserva su significado y un schema de payload futuro falla cerrado.
- En Paper 1.21.11 `InventoryCloseEvent` ocurre antes de `AbstractContainerMenu.removed`; close,
  quit y kick limpian solo la proyección exacta y encolan un settlement de fallback sin esperar
  I/O. Death/disable estacionan CURSOR_STABLE para recovery. Un tag escapado exige write-ahead
  exacto tagged BEFORE -> canonical AFTER -> saveData -> ACK; 0/2 tags o id/secuencia/fingerprint
  divergente quedan bloqueados, sin búsqueda por material/meta ni drops heurísticos.
- `/venderchestadmin storage-migrate dry-run|start|resume|status` copia SQLite a un MySQL
  separado en maintenance. La fuente abre `mode=ro`/`query_only`, la huella lógica incluye
  schema y filas ordenadas, el checkpoint COW avanza después del commit y un conflicto nunca
  sobrescribe. SQLite no se borra, renombra ni altera.
- Smoke descartable 1.1.0 verificado con Paper 1.21.11-132, MySQL 8.4.10 y Redis 3.0.504:
  lifecycle `ACTIVE`, esquema InnoDB/utf8mb4 v1, lease real SET/renew/release, exclusión del
  segundo coordinador, fence monotónico con hora MySQL, migración dry-run/start/status y resume
  tras commit sin checkpoint, segundo arranque y apagado limpio. La prueba detectó y corrigió
  el sombreado de SLF4J para reutilizar el API/binding provisto por Paper.
- Servicios smoke sanitizados del RC anterior: se ejecutaron
  `/venderchestadmin storage-migrate dry-run`, `start` y `status` hasta `COMPLETED`, más
  recovery `resume` después de commit sin checkpoint. No validan la GUI escrow nueva.
  `clean test build` actual terminó 137/137. Runtime vigente:
  `3DA7F11DF97E4A9FE96586D644970B5274EE1EE3C51D02CFD6E5FDDE4AAB3867`.
  Los artefactos `CA4A809B8742FC831345FFAE4DF776215414262C62CE25BC93371471EF58B7E3`,
  `3F927A7E2C4FBDED580AFCB084728EC6F908F8F25B05CCC1670F94D65A1AC01B`
  y `584ECD3BBFD2A1326D8109BAE2EEEE1CD9A021D469071606F7D1A768DB392608`
  están supersedidos y no deben usarse;
  Paper: `5FFEF465EEEB5F2A3C23A24419D97C51AFD7DBB4923FF42DF9A3F58BBA1CCFBA`;
  MySQL: `3B950DB31C33FB59252568C012BD9EE5FAC50811E778CA7C8F1A0DC91686CD6F`;
  Redis: `5F761367601CA31F6C8969E427CACC0DA4F428712954A66AAB303F83E390566E`.

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
- MySQL es la única autoridad cross-server; Redis nunca contiene el vault autoritativo.
- El cursor CROSS nunca es autoridad: es una proyección de CURSOR_STABLE y no cuenta en el
  multiset de conservación. Ningún ACK puede coexistir con escrow/proyección vigente; los finales
  eliminan exclusivamente `escrow_id` y `op_sequence` propios. Vault + escrow + playerdata,
  agrupados por fingerprint y cantidad, se conservan o el owner termina QUARANTINED.
- Maintenance se publica antes de cerrar SQLite y bloquea aperturas, writers y operaciones
  nuevas hasta reiniciar, incluso si la migración falla.

## Bloqueo conocido

La compuerta de producción sigue cerrada. El dominio, empaquetado, suite automatizada y smoke
de servicios MySQL+Redis reales están verificados, pero no hubo cliente real ni dos vistas GUI
simultáneas del mismo owner. Redis Windows 3.0.504 solo validó comandos básicos, Lua y TTL; no
representa una versión soportada de producción ni valida ACL/TLS. No se reemplaza esa evidencia
con jugadores o eventos simulados.

Antes de producción: repetir con el Redis soportado actual de la red (preferiblemente 7.x), dos
Paper con `network` compartida y `server-id` únicos, y cliente real. Verificar A commit -> B visible,
apertura simultánea `BUSY`, kill A -> expiración Redis/DB -> recovery B, replay PREPARED y
DB_COMMITTED, inventario lleno y restart.

## Próximo trabajo

1. Migrar los mensajes hardcodeados restantes de `EcAdminCommand`.
2. Añadir paginación reutilizable al renderer de help cuando supere ocho entradas.
3. Ejecutar y ampliar la suite adversarial anti-dupe.
4. Auditar todas las transiciones de sesión y rutas de cierre.
5. Repetir el smoke Paper aislado en cada release que cambie dependencias o concurrencia.
6. Mantener Paper 1.21.11 como API mínima y bytecode Java 21.
7. Validar también la última versión estable de Paper con su Java requerida.
8. Ejecutar smoke tests de apertura, conflicto, desconexión, reload y apagado.

## Documentos relacionados

- `docs/DUPLICATION_FIX.md`
- `docs/VANTIDUPE_API.md`
- `docs/MIGRATION_REVISION.md`
