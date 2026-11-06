# MMAC: Minimal Music Assistant Companion

Una aplicación complementaria, simplificada y básica para conectar con tu servidor de **Music Assistant** y reproducir música en tu dispositivo móvil.

---

## ⚠️ Aviso y Estado del Proyecto

- **Versión No Oficial**: Esta aplicación **NO** es la versión oficial de Music Assistant ni cuenta con afiliación oficial con el equipo central de Music Assistant ni con la Open Home Foundation.
- **Mantenimiento**: Es un proyecto independiente desarrollado y mantenido actualmente por **una única persona**.
- **Propósito**: Ofrecer una alternativa liviana, minimalista y ágil para controlar la reproducción de tu biblioteca personal y actuar como reproductor de audio local mediante el protocolo Sendspin.

---

## 🎵 Características Principales

- **Conexión Directa con Music Assistant**:
  - Compatible con servidores Music Assistant 2.0 y versiones superiores.
  - Comunicación en tiempo real a través de WebSocket (`/ws`) y llamadas API REST (`/api`).
  - Soporte de autenticación mediante usuario/contraseña o token de acceso de larga duración (*Long-Lived Access Token*).
  - Eventos en vivo para actualización automática del estado de reproducción, volumen, colas y metadatos.

- **Reproductor Local Sincronizado (Sendspin)**:
  - Integra soporte para el protocolo de audio multi-room **Sendspin** (`player@v1`).
  - Convierte tu teléfono móvil en un reproductor nativo visible en la lista de altavoces del servidor.
  - Sincronización precisa con reloj de alta resolución (filtro Kalman 2D y alineación de marcas de tiempo).
  - Ajuste de compensación de retardo de altavoz (*Speaker Delay Compensation*) para sincronización exacta con otros altavoces del hogar o para compensar la latencia de auriculares Bluetooth.

- **Biblioteca y Reproducción**:
  - Exploración rápida de Canciones, Artistas, Álbumes y Listas de reproducción.
  - Búsqueda instantánea en la biblioteca.
  - Gestión y visualización de la cola de reproducción actual.
  - Mini reproductor persistente y reproductor inmersivo a pantalla completa.
  - Control de volumen, pausa, salto de pista y modo aleatorio / repetición.

- **Personalización y Modo Demo**:
  - Selección de colores de acento personalizables.
  - Modo Demostración integrado para explorar la interfaz sin necesidad de conexión inmediata a un servidor físico.

---

## 🛠️ Configuración Rápida

1. Abre la pestaña **Ajustes** en la aplicación.
2. Ingresa a **Configuración del Servidor**:
   - **Dirección IP / Host**: La IP local o dominio de tu servidor (ej. `192.168.1.50`).
   - **Puerto**: Generalmente `8095` para Music Assistant.
   - **Token o Credenciales**: Ingresa tu token de larga duración o tus credenciales si tu servidor tiene autenticación activada.
3. Presiona **Probar Conexión** para verificar el enlace y luego **Guardar**.
4. Si deseas escuchar música en el altavoz o auriculares de este teléfono:
   - Activa la opción **Reproductor Local (este teléfono)**.
   - Configura el nombre con el que aparecerá en Music Assistant (ej. `Mi Teléfono`).
   - El puerto por defecto para Sendspin es `8927`.
   - Una vez conectado, selecciona este reproductor en la lista de reproductores de Music Assistant para enviarle audio sincronizado.

---

## 📜 Atribuciones y Reconocimientos

Este proyecto se apoya en el trabajo, especificaciones e iniciativas de la comunidad de software libre:

- **Music Assistant**:
  - Creado y liderado por **Marcel van der Veldt** y la comunidad de colaboradores de Music Assistant.
  - Sitio oficial: [music-assistant.io](https://music-assistant.io/)
  - Repositorio principal: [github.com/music-assistant/server](https://github.com/music-assistant/server)
  - Repositorio de la aplicación móvil oficial: [github.com/music-assistant/mobile-app](https://github.com/music-assistant/mobile-app)

- **Protocolo Sendspin**:
  - Diseñado y desarrollado bajo el patrocinio de la **Open Home Foundation** y la comunidad de Music Assistant para streaming de audio multi-habitación abierto, sin regalías y de baja latencia.
  - Sitio de la especificación: [sendspin-audio.com](https://www.sendspin-audio.com/)
  - Repositorios del protocolo: [github.com/Sendspin](https://github.com/Sendspin)

- **Librerías de Terceros de Código Abierto**:
  - **OkHttp**: [Square, Inc.](https://square.github.io/okhttp/) (Licencia Apache 2.0).
  - **Coil**: Carga eficiente de imágenes para Compose (Licencia Apache 2.0).
  - **Jetpack Compose y Kotlin Coroutines**: Herramientas y bibliotecas de código abierto bajo Licencia Apache 2.0.

---

## 📄 Licencia

Este proyecto se distribuye como software libre para la comunidad de usuarios de Music Assistant. Consulta el archivo de licencia correspondiente para más detalles.

## Privacy policy

MMAC's privacy policy is available in [English and Spanish](https://lean724.github.io/Music-Assistant-AP/privacy-policy.html). The page is also linked from the app's Settings screen.
