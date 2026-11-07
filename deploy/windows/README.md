# Despliegue en Windows

El equivalente a systemd aquí es una tarea programada que arranca con el sistema. Todo vive en el
directorio donde pongas los ficheros; `C:\uploadguard` sirve igual que cualquier otro.

Requisitos: JDK 21+ en el PATH.

> **Diferencia real con Linux, no la disimulo:** el instalador de Linux crea un usuario de sistema
> `uploadguard` y el servicio corre como él. Aquí la tarea corre como **SYSTEM**, porque un `.cmd` no
> puede crear una cuenta de servicio sin contraseña de forma razonable. Si necesitas esa aislación,
> despliega en Linux o usa un envoltorio de servicio en serio (NSSM, WinSW).

## 1. Dejar los ficheros en su sitio

Descarga `uploadguard-<version>-windows.zip` de las
[releases](https://github.com/peluware/uploadguard/releases) y descomprímelo donde quieras instalarlo;
el zip contiene el directorio `uploadguard\` con el jar y los scripts. Luego continúa en el paso 2.

Para construirlo tú mismo en vez de descargarlo:

```bat
mvn clean package
```

Y deja esto en el directorio de instalación:

```
C:\uploadguard\
├── uploadguard.jar          ← de target\ tras el build
├── install.cmd
├── uninstall.cmd
├── run.cmd
└── env.example
```

Todo eso menos el jar sale de este directorio del repo.

## 2. Instalar

Símbolo del sistema **como administrador**, desde el directorio:

```bat
cd C:\uploadguard
install.cmd
```

Registra la tarea `UploadGuard`, crea el `.env` desde la plantilla si falta, y arranca.
Re-ejecutarlo reemplaza la tarea; el `.env` no se toca. La primera vez para antes de arrancar, para
que configures.

## 3. Configurar

```bat
notepad C:\uploadguard\.env
```

```ini
UPSTREAM_URL=http://127.0.0.1:3900
PORT=8080
```

`UPSTREAM_URL` es la dirección interna del backend, no el dominio público.

```bat
schtasks /run /tn "UploadGuard"
```

## 4. Verificar

No hace falta firma ni reverse proxy: el rechazo ocurre antes de hablar con el backend.

```bat
curl -s -o NUL -w "%%{http_code}\n" -X PUT -H "Content-Type: image/png" --data-binary @C:\Windows\notepad.exe http://127.0.0.1:8080/x
```

Esperado **`415`**: un ejecutable declarado como PNG, y ese código solo lo emite UploadGuard.

## 5. Actualizar

Para el servicio, reemplaza `uploadguard.jar` y vuelve a arrancarlo:

```bat
schtasks /end /tn "UploadGuard"
copy /y ...\target\uploadguard.jar C:\uploadguard\uploadguard.jar
schtasks /run /tn "UploadGuard"
```

## 6. Desinstalar

Como administrador:

```bat
uninstall.cmd
```

Quita la tarea. Los ficheros se quedan donde están: el directorio lo elegiste tú, así que borrarlo es
tu decisión.

## Operar

```bat
schtasks /query /tn "UploadGuard" /fo list
```

La tarea no captura la salida. Para ver los logs, párala y ejecútalo en primer plano:

```bat
schtasks /end /tn "UploadGuard"
run.cmd
```

Ahí ves la línea de arranque y los rechazos con el tipo declarado y el detectado.

## Probarlo sin instalar nada

```bat
run.cmd
```

En primer plano, leyendo el `.env` del directorio.
