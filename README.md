<div align="center">

# vEnderchest

**Enderchests multi-página con persistencia segura para Paper.**

[![Version](https://img.shields.io/badge/version-1.1.0-a970ff?style=flat-square)](https://github.com/ValerinSMP/vEnderchest)
[![Paper](https://img.shields.io/badge/Paper-1.21.11%2B-222222?style=flat-square)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-E76F00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)

[Características](#-características) · [Instalación](#-instalación) · [Comandos](#-comandos) · [Desarrollo](#-desarrollo) · [Documentación](#-documentación-y-licencia)

</div>

**vEnderchest** reemplaza el enderchest tradicional por vaults configurables con
sesiones autoritativas, control de revisiones y herramientas de administración.
Otros plugins pueden observar sus operaciones mediante una API pública inmutable.

## ⭐ Características

- ⭐ Hasta nueve páginas, controladas mediante permisos.
- ⭐ Persistencia en SQLite o MySQL con escrituras CAS y backups automáticos.
- ⭐ Protección anti-dupe con sesiones autoritativas y reversión de conflictos.
- ⭐ Coordinación cross-server opcional con MySQL autoritativo, leases Redis y fencing persistente.
- ⭐ Vista, edición, limpieza y restauración administrativa de vaults.
- ⭐ Migración desde enderchests vanilla y AxVaults.
- ⭐ PlaceholderAPI opcional y API pública inmutable para integraciones.

## 🧰 Compatibilidad

| Paper | Java | Estado |
| :---: | :---: | :---: |
| `1.21.11` | `21` | Versión mínima compatible |
| `26.1+` | `25` | Versiones modernas de Paper |

- No compatible con Folia, Bukkit, Spigot ni Arclight.
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) es opcional.
- Un servidor puede usar SQLite sin servicios externos.
- El modo cross-server requiere MySQL y Redis accesibles desde cada servidor; Redis coordina
  leases, mientras MySQL conserva siempre el contenido autoritativo.

## 📦 Instalación

1. Compila o descarga el jar de runtime `vEnderchest-1.1.0.jar`.
2. Colócalo en `plugins/` e inicia Paper una vez.
3. Configura SQLite o MySQL en `plugins/vEnderchest/config.yml`.
4. Revisa permisos, migraciones y backups antes de abrir el servidor.

No instales `vEnderchest-1.1.0-api.jar` en el servidor: ese artefacto es únicamente
para dependencias `compileOnly` de otros plugins.

Para ocultar las líneas `[audit]` de la consola, establece
`audit.console-enabled: false` y ejecuta `/venderchestadmin reload`. La protección,
la persistencia, los eventos API y la detección de conflictos continúan activas.

### Cross-server

Todos los servidores de la red deben compartir `database.mysql`, `database.table-prefix` y
`cross-server.network`; cada uno debe tener un `cross-server.server-id` distinto:

```yaml
database:
  type: mysql
  table-prefix: ec_
  mysql:
    host: mysql
    port: 3306
    database: venderchest
    username: venderchest
    password: ""

cross-server:
  enabled: true
  network: valerin
  server-id: survival-1
  redis:
    host: redis
    port: 6379
```

Completa las credenciales únicamente en el `config.yml` privado del servidor. Si MySQL, Redis,
el lease o su heartbeat pierden autoridad, vEnderchest falla cerrado: no acepta nuevas mutaciones
ni permite que un writer antiguo sobrescriba una revisión nueva.

Dentro del vault, Bukkit aplica inmediatamente los movimientos normales de inventario. vEnderchest
captura el resultado al tick siguiente y lo guarda con revisión CAS y fence mientras conserva el
lease de la sesión. Esto evita dos writers cross-server, pero MySQL y el inventario del jugador no
forman una transacción única: un kill/crash antes de que ambos estados queden durables puede perder
o duplicar el último movimiento.

### Migrar SQLite a MySQL

Ejecuta el flujo desde consola mientras `database.type: sqlite` sigue activo:

1. `/venderchestadmin storage-migrate dry-run`
2. Con cero jugadores y sesiones: `/venderchestadmin storage-migrate start`
3. Si el proceso o servidor se interrumpe: `/venderchestadmin storage-migrate resume`
4. Revisa `/venderchestadmin storage-migrate status` hasta obtener `COMPLETED`.
5. Cambia `database.type` a `mysql`, configura `cross-server.enabled` si corresponde y reinicia.

`start` publica maintenance antes de cerrar SQLite y el plugin permanece así hasta reiniciar,
incluso si hay un fallo. La copia es reanudable, nunca sobrescribe conflictos y **nunca borra,
renombra ni altera el SQLite original**.

## 🎮 Comandos

| Comando | Descripción | Permiso |
| --- | --- | --- |
| `/venderchest help` | Muestra la ayuda interactiva. | Todos |
| `/venderchest about` | Muestra versión, autor y plataforma. | Todos |
| `/ec [página]` | Abre el enderchest extendido. | `venderchest.use` |
| `/venderchestadmin view <jugador> [página]` | Visualiza el vault de otro jugador. | `venderchest.admin.view` |
| `/venderchestadmin clear <jugador> [página]` | Limpia una página. | `venderchest.admin` |
| `/venderchestadmin addvault <jugador> <cantidad>` | Añade páginas extra. | `venderchest.admin` |
| `/venderchestadmin removevault <jugador> <cantidad>` | Retira páginas extra. | `venderchest.admin` |
| `/venderchestadmin migrate ...` | Consulta o reinicia una migración. | `venderchest.admin` |
| `/venderchestadmin storage-migrate <dry-run\|start\|resume\|status>` | Copia SQLite a MySQL de forma offline y reanudable. | `venderchest.admin` |
| `/venderchestadmin restore <jugador> [id] [confirm]` | Lista, previsualiza o restaura backups. | `venderchest.admin` |
| `/venderchestadmin reload` | Recarga configuración y mensajes. | `venderchest.admin` |

Las páginas adicionales usan permisos `venderchest.pages.1` hasta
`venderchest.pages.9`.

## 🛠️ Desarrollo

```powershell
.\gradlew.bat clean test build
```

Estado verificado de 1.1.0: **137 pruebas en 26 suites, 0 fallos**.

Artefactos esperados:

- Runtime sombreado para el servidor: `build/libs/vEnderchest-1.1.0.jar`
- API pública liviana para `compileOnly`: `build/libs/vEnderchest-1.1.0-api.jar`

El proyecto usa Gradle Kotlin DSL, Paper API 1.21.11 y toolchain Java 21. El
versionado sigue [SemVer](https://semver.org/lang/es/).

## 📚 Documentación y licencia

- [`docs/VANTIDUPE_API.md`](docs/VANTIDUPE_API.md)
- [`docs/DUPLICATION_FIX.md`](docs/DUPLICATION_FIX.md)
- [`docs/MIGRATION_REVISION.md`](docs/MIGRATION_REVISION.md)
- [`THIRD-PARTY-NOTICES.txt`](src/main/resources/THIRD-PARTY-NOTICES.txt)
- [Repositorio y seguimiento de cambios](https://github.com/ValerinSMP/vEnderchest)

Este repositorio no incluye actualmente un archivo de licencia pública.
