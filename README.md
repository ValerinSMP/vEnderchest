# vEnderchest

Vault multi-página para Paper/UniverseSpigot 1.21.11 y Java 21.

La versión 1.1.1 incorpora sesiones autoritativas, revisión CAS y una API
inmutable para monitores pasivos como vAntiDupe.

La 1.1.1 corrige además la migración de cofres creados antes de existir la
columna `revision`: esas filas empiezan en revisión `0` y ahora se actualizan
atómicamente a revisión `1` en su primer guardado. En 1.1.0 se confundían con
filas inexistentes, provocando conflictos `0→0` y omitiendo el guardado.

Documentación:

- `docs/DUPLICATION_FIX.md`
- `docs/VANTIDUPE_API.md`
- `docs/MIGRATION_REVISION.md`

Build:

```powershell
.\gradlew.bat clean test build
```

Artefactos:

- `build/libs/vEnderchest-1.1.1.jar`
- `build/libs/vEnderchest-1.1.1-api.jar`

La protección verificada cubre reaperturas y paquetes antiguos dentro del
servidor. Un mismo vault no debe abrirse simultáneamente en modo escritura
desde dos servidores compartiendo MySQL hasta añadir un lease distribuido.
Enderchest extendido multi-página
