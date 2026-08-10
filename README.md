<div align="center">

# vEnderchest

### Enderchests multi-página, persistentes y seguros para Paper

[![Paper](https://img.shields.io/badge/Paper-1.21.11%2B-222222?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-E76F00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/version-1.0.2-7B5CFA?style=for-the-badge)](https://github.com/ValerinSMP/vEnderchest)

</div>

**vEnderchest** reemplaza el enderchest tradicional por un vault configurable de
múltiples páginas. Está diseñado para conservar ítems de forma segura, ofrecer
herramientas administrativas y permitir que otros plugins observen sus operaciones
mediante una API pública inmutable.

## ⭐ Características

- **Múltiples páginas:** cantidad controlada mediante permisos.
- **Persistencia flexible:** SQLite para instalaciones locales y MySQL para redes.
- **Sesiones autoritativas:** cada apertura mantiene un estado de sesión explícito.
- **Control de revisiones:** escrituras CAS para detectar modificaciones conflictivas.
- **Protección anti-dupe:** validación de transacciones y registro de diferencias.
- **Reversión de conflictos:** una transferencia rechazada se deshace antes de navegar.
- **Backups:** navegación y previsualización de copias guardadas.
- **Migraciones:** importación desde enderchests vanilla y AxVaults.
- **Administración:** vista y edición de vaults con permisos separados.
- **PlaceholderAPI:** integración opcional para mostrar información del plugin.
- **Developer API:** jar liviano con DTOs, vistas y eventos públicos.

## Versionado

`1.0.0` establece el nuevo baseline SemVer de vEnderchest. A partir de este punto,
las correcciones compatibles incrementan PATCH, las funciones compatibles incrementan
MINOR y los cambios incompatibles incrementan MAJOR.

## 🔒 Seguridad de inventarios

El baseline 1.0.0 incorpora revisiones CAS, sesiones controladas y una corrección para
vaults creados antes de existir la columna `revision`.

Desde 1.0.2, las aperturas también se ordenan por actor: un resultado asíncrono antiguo
no puede reemplazar una GUI solicitada después, aunque pertenezcan a páginas distintas.
Los cambios de vista iniciados por clicks se difieren al tick siguiente, como exige Paper.

Si una revisión cambia mientras una página está abierta, el plugin revierte el
balance transferido entre vault e inventario. Así un objeto no puede conservarse en
la página anterior y depositarse después en otra.

Un mismo vault no debe abrirse simultáneamente en modo escritura desde dos servidores
que compartan MySQL. El soporte de escritura cross-server se habilitará únicamente
cuando exista coordinación distribuida con garantías suficientes.

## 🎮 Comandos

| Comando | Descripción | Permiso |
| --- | --- | --- |
| `/venderchest help` | Muestra la ayuda interactiva. | Todos |
| `/venderchest about` | Muestra versión, autor y plataforma. | Todos |
| `/venderchestadmin reload` | Recarga configuración y mensajes. | `venderchest.admin` |
| `/ec [página]` | Abre el enderchest extendido. | `venderchest.use` |
| `/venderchestadmin view <jugador> [página]` | Visualiza el vault de otro jugador. | `venderchest.admin.view` |
| `/venderchestadmin clear <jugador> [página]` | Administra el contenido de un vault. | `venderchest.admin.edit` |

Las páginas adicionales usan permisos `venderchest.pages.1` hasta
`venderchest.pages.9`.

## 🧰 Requisitos

| Paper | Java requerida | Folia | Arclight |
| :---: | :---: | :---: | :---: |
| 1.21.11 | 21 | ❌ | ❌ |
| 26.1 en adelante | 25 | ❌ | ❌ |

Dependencia opcional:

- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)

## 📦 Instalación

1. Compila o descarga `vEnderchest-1.0.2.jar`.
2. Copia el jar dentro de `plugins/`.
3. Inicia Paper para generar la configuración.
4. Selecciona y configura SQLite o MySQL.
5. Reinicia el servidor después de revisar permisos y migraciones.

Realiza un backup antes de cambiar el backend de almacenamiento o importar datos.

Para silenciar las líneas estructuradas `[audit]` de la consola, configura
`audit.console-enabled: false` y ejecuta `/venderchestadmin reload`. Esto no desactiva
la protección anti-dupe, la persistencia, los eventos de la API ni los conflictos.

## 🛠️ Compilación

```powershell
.\gradlew.bat clean test build
```

Artefactos esperados:

- `build/libs/vEnderchest-1.0.2.jar`
- `build/libs/vEnderchest-1.0.2-api.jar`

## 🧩 API y documentación

- [`docs/VANTIDUPE_API.md`](docs/VANTIDUPE_API.md)
- [`docs/DUPLICATION_FIX.md`](docs/DUPLICATION_FIX.md)
- [`docs/MIGRATION_REVISION.md`](docs/MIGRATION_REVISION.md)
