# Jocotoco SensorNode — v0.1.0 (prototipo Android)

**Objetivo:** reutilizar un Samsung Galaxy J1 mini Prime **SM-J106M**, Android 6.0.1, como nodo de adquisición de cámara, micrófono y sensores administrado desde Windows/Linux por el SSH de Termux ya instalado.

> Estado: **fuentes preparadas, aún sin APK compilada ni prueba en el J1 real**. La compilación y las pruebas de cámara/audio deben realizarse en Android Studio o CI y después en el teléfono. El archivo ZIP **no contiene un APK listo para instalar**.

## Funcionamiento

- APK nativa Java, `minSdk 23` (Android 6), `targetSdk 28`, sin root ni librerías de terceros en tiempo de ejecución.
- Servicio Android con notificación persistente, inicia desde la app, abre HTTP **solo** en `127.0.0.1:8765` **dentro del teléfono**.
- Autenticación con token aleatorio de 48 caracteres hexadecimales guardado en preferencias privadas y visible en pantalla. El token **no** atraviesa la red local en texto plano porque usamos un túnel SSH.
- Fotografía JPEG con **máxima resolución que informe la API de cámara**, sin aumentar la imagen artificialmente. Cámara orientada al sensor (no se corrige automáticamente la rotación EXIF).
- Audio WAV mono PCM 16 bits a 16.000 Hz; grabación de 1 a 60 segundos. **No** graba audio continuamente ni transmite un stream en vivo.
- Batería, modelo, versión Android, memoria, lista de cámaras y sensores y lectura temporal de sensores, y descarga de archivos.
- Archivos guardados en almacenamiento privado de la app; no requieren permiso de tarjeta SD.
- API `/v1/health`, `/v1/status`, `/v1/sensors`, `/v1/photo`, `/v1/audio`, `/v1/files`, `/v1/file?name=...`.

## 1. Compilar la APK

### Opción A — Android Studio (Windows/Linux)

1. Abrir la carpeta raíz `Jocotoco_SensorNode` como proyecto Gradle en Android Studio.
2. Tener JDK 17 y Android SDK Platform 35 instalado. AGP 8.6.1 / Gradle 8.7 son la configuración prevista.
3. Configurar en Android Studio un Gradle local 8.7 o añadir un wrapper de Gradle usando `gradle wrapper --gradle-version 8.7` y después `gradlew.bat assembleDebug` (Windows) o `./gradlew assembleDebug` (Linux).
4. Compilar con **Build > Build Bundle(s)/APK(s) > Build APK(s)**.
5. Encontrar `app/build/outputs/apk/debug/app-debug.apk`.

No incluimos `gradle-wrapper.jar` porque no está disponible en el entorno de generación. La opción CI no necesita ese archivo.

### Opción B — GitHub Actions (recomendación si no tienes Android Studio)

1. Crear un repositorio **privado** GitHub; subir el **contenido** de esta carpeta (incluido `.github`).
2. Abrir pestaña **Actions** > **Compilar APK SensorNode** > **Run workflow**.
3. Descargar el artefacto `Jocotoco_SensorNode_v0.1.0_debug`.
4. Descomprimir el artefacto para obtener `app-debug.apk`.

La compilación usa JDK 17, Gradle 8.7, SDK 35, genera APK firmada para **depuración**. No es una publicación comercial. Debe verificarse que CI compile correctamente.

## 2. Instalar en el J1

Transferir `app-debug.apk` al teléfono y abrirlo desde su administrador de archivos, permitiendo la instalación desde orígenes desconocidos solo si lo apruebas. Alternativa con Android Debug Bridge por USB:

```powershell
adb devices
adb install -r .\app-debug.apk
```

El teléfono debe mostrar el botón **Solicitar permisos**. Conceder Cámara y Micrófono y después pulsar **Iniciar servidor local**. Copiar o transcribir el token desde la pantalla; **no publicarlo**. El servicio requiere que el usuario abra la APK por primera vez.

## 3. Abrir túnel SSH desde Windows (dos consolas PowerShell)

En **PowerShell 1**:

```powershell
ssh -N -L 8765:127.0.0.1:8765 -p 8022 u0_a112@192.168.100.184
```

La contraseña es la que ya recordaste para Termux. **Mantén abierta** esta consola mientras usas el sensor. Este comando aprovecha la red actual y el SSH funcional; **no instala ni modifica nada en Termux**.

En **PowerShell 2** (en la carpeta raíz del proyecto):

```powershell
python .\client\sensornode.py health
python .\client\sensornode.py status
python .\client\sensornode.py sensors
python .\client\sensornode.py photo
python .\client\sensornode.py audio --seconds 10
python .\client\sensornode.py files
```

La primera orden `health` comprueba que responde la APK y **no** necesita token. El cliente solicitará el token de la pantalla de la APK para el resto de las órdenes. Las imágenes y el audio se descargan a `capturas_j1/` automáticamente.

Para no escribir el token repetidamente, en una sesión PowerShell temporal:

```powershell
$env:JOCOTOCO_TOKEN = Read-Host "Token de SensorNode"
python .\client\sensornode.py photo
Remove-Item Env:JOCOTOCO_TOKEN
```

No uses IP `192.168.1.13`, que era la antigua del teléfono. La foto actual muestra `192.168.100.184/24`; esa IP **puede cambiar**.

## 4. API desde Python

Ejemplo mínimo **desde el PC con túnel SSH**:

```python
import urllib.request, json, getpass
TOKEN = getpass.getpass('Token: ')
request = urllib.request.Request('http://127.0.0.1:8765/v1/photo?facing=back',
    headers={'X-SensorNode-Token': TOKEN}, method='POST', data=b'')
with urllib.request.urlopen(request, timeout=30) as resp:
    photo = json.load(resp)
print(photo)
```

El campo `download` que devuelve el servicio permite bajar el archivo mediante `GET /v1/file?name=...`, enviando la misma cabecera de token.

## 5. Notas técnicas importantes

- **Android no es Ubuntu**: SSH llega a Termux, pero la APK es quien accede a `Camera`, `AudioRecord`, `SensorManager`, no a los dispositivos `/dev` arbitrariamente.
- La API se liga **solo a loopback del teléfono**. Jamás uses un reenvío abierto tipo `0.0.0.0` ni expongas el puerto a Internet. El dispositivo ejecuta Android 6, sin soporte de seguridad actualizado.
- Las pruebas no son aún garantía: dispositivos Samsung antiguos pueden exigir ajustes en la inicialización de la cámara, el enfoque, audio o suspensión del sistema. El primer paso de validación real es `health` y `status`, luego `photo` y `audio`.
- Si la cámara es usada por la aplicación Cámara o si el micrófono está ocupado, puede devolver error. Cierra otras apps que utilicen estos dispositivos antes de probar.
- La versión 0.1 guarda los archivos **solo en la app**. Desinstalarla puede borrar sus fotos y grabaciones: descárgalas antes.
- El servicio foreground evita algunas suspensiones, pero no garantiza funcionamiento permanente en todos los estados de Android 6. No se configuró inicio automático ni control de energía avanzada.
- GPS no está implementado en esta versión; requeriría permisos y una ruta de lectura dedicados.
- El método de superresolución multiframe es una **etapa posterior**, una vez validada la captura nativa de JPEG repetida y, si existe, la exposición manual/RAW del dispositivo.

## 6. Próximas versiones

v0.1.1: diagnósticos de errores y ajuste de captura según el J1 físico. v0.2: captura programada y muestreo repetitivo, parámetros de cámara y audio, sincronización temporal. v0.3: streaming de bajo ancho de banda, colas de archivos, control de energía y watchdog. Posteriormente: exposición/RAW si hardware/API los permiten y pruebas de superresolución con imágenes reales.

## Estructura

```
Jocotoco_SensorNode/
├── app/src/main/               # APK Android 6, Java
│   ├── AndroidManifest.xml
│   └── java/org/jocotoco/sensornode/
│       ├── MainActivity.java    # Pantalla, permisos, token
│       ├── SensorNodeService.java  # Servicio foreground
│       ├── ApiServer.java       # HTTP privado
│       ├── Hardware.java        # Cámara, audio, batería, sensores
│       └── Secrets.java         # Token aleatorio
├── client/sensornode.py         # CLI Python para Windows/Linux
├── .github/workflows/build-apk.yml
├── tests/test_client.py
└── README.md
```

## Pruebas de cliente local

```bash
python -m unittest discover -s tests -v
```
