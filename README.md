# Envío 1702 — App del cadete (Android)

App de Android que abre la app del cadete de Envío 1702 y le suma lector de QR nativo, cámara, ubicación y apertura de WhatsApp / Google Maps / llamadas.

La app carga la versión web publicada en Google Apps Script, así que los cambios que se hagan ahí se ven solos, sin reinstalar.

## Cómo se compila

GitHub la compila sola cada vez que se suben cambios (pestaña **Actions** → último "Compilar app" → **Artifacts** → `Envio1702-apk`).

Para compilar a mano: JDK 17, Android SDK 34 y Gradle 8.7 → `gradle assembleRelease`.

El APK de esta versión está firmado con la clave de prueba de Android: sirve para instalar directo en los teléfonos. Para publicar en Google Play hay que generar una clave propia.
