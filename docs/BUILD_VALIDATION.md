# Build validado 1.1.0

Fecha: 2026-07-26

- Java: 21
- Paper API: 1.21.11-R0.1-SNAPSHOT
- pruebas: 25
- fallos: 0
- errores: 0

## JAR de servidor

- archivo: `build/libs/vEnderchest-1.1.0.jar`
- tamaño: 9,836,384 bytes
- SHA-256:
  `0487A1D6EA3EDE609119266328B38DBA9C3D0C0C115484D80A70E062FCF4E6F9`

## JAR de API compileOnly

- archivo: `build/libs/vEnderchest-1.1.0-api.jar`
- tamaño: 14,868 bytes
- SHA-256:
  `94916F8190FA2BBEF2761AFDB3F74BE3FC64E45D5CDE852E842939AEA909FEA9`

El JAR `-api` es únicamente para compilar integraciones. No debe instalarse
como plugin separado en el servidor; la API ya está incluida en el JAR
principal.
