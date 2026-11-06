# Despliegue en Linux (systemd)

Todo vive en `/opt/uploadguard`: el jar, la configuración, la unidad y los scripts. En `/etc` solo
queda un symlink que systemd gestiona solo.

Requisitos: JDK 21+ en el servidor. Maven no hace falta ahí.

## 1. Instalar

Construye el jar donde tengas Maven:

```bash
mvn clean package
```

En el servidor:

```bash
mkdir -p /opt/uploadguard
```

Sube el jar y el contenido de este directorio:

```bash
scp target/uploadguard.jar deploy/linux/* root@servidor:/opt/uploadguard/
```

Y en el servidor:

```bash
bash /opt/uploadguard/install.sh
```

Debe quedar así antes de instalar:

```
/opt/uploadguard/
├── uploadguard.jar          ← de target/ tras el build
├── install.sh
├── uninstall.sh
├── run.sh
├── uploadguard.service
└── env.example
```

Y así después, con dueños y modos:

```
├── uploadguard.jar          root:root        644
├── .env                     root:uploadguard 640   ← lo único que lee el servicio
├── install.sh  uninstall.sh  run.sh
├── uploadguard.service      ← la unidad real; /etc solo tiene symlinks
└── env.example
```

El instalador crea el usuario de sistema, el `.env` si falta, ajusta permisos, resuelve dónde está
java, habilita la unidad y para pidiéndote que configures.

Es idempotente: un `=` es lo que ya estaba, un `+` lo que cambió. Solo reinicia si cambió algo, y
nunca toca un `.env` existente.

> Si entras con un usuario normal en vez de root, antepón `sudo` al `mkdir` y al `install.sh`, y dale
> el directorio a tu usuario mientras copias: `sudo chown $(id -un) /opt/uploadguard`. El instalador
> se lo devuelve a root al terminar.

## 2. Configurar

```bash
sudo nano /opt/uploadguard/.env
```

```ini
UPSTREAM_URL=http://127.0.0.1:3900
PORT=8080
```

`UPSTREAM_URL` es la dirección interna del backend, no el dominio público.

```bash
sudo systemctl start uploadguard
journalctl -u uploadguard -n 20 --no-pager
```

Esperado:

```
INFO: UploadGuard listening on port 8080, forwarding to http://127.0.0.1:3900
```

## 3. Verificar

No hace falta firma ni reverse proxy: el rechazo ocurre antes de hablar con el backend.

```bash
# un binario declarado como PNG -> 415, y ese código solo lo emite UploadGuard
curl -s -o /dev/null -w '%{http_code}\n' -X PUT -H 'Content-Type: image/png' \
  --data-binary @/bin/ls http://127.0.0.1:8080/x
```

```bash
# contenido que sí es lo que dice -> cualquier cosa menos 415, porque se reenvía
printf '\x89PNG\r\n\x1a\n' > /tmp/ok.png
curl -s -o /dev/null -w '%{http_code}\n' -X PUT -H 'Content-Type: image/png' \
  --data-binary @/tmp/ok.png http://127.0.0.1:8080/x
```

Con esas dos líneas sabes que está vivo, que detecta y que reenvía.

## 4. Actualizar

Reemplaza `uploadguard.jar` y vuelve a ejecutar el instalador. El `.env` se queda como está.

## 5. Desinstalar

```bash
sudo bash /opt/uploadguard/uninstall.sh                # todo: servicio, directorio y usuario
sudo bash /opt/uploadguard/uninstall.sh --keep-config  # conserva el .env
```

Imprime el `.env` antes de borrarlo. También es idempotente.

## Operar

```bash
systemctl status uploadguard
journalctl -u uploadguard -f
```

Los rechazos salen con los dos tipos, que es toda la información que necesitas:

```
Rejected PUT /uploads/foto.png over HTTP/1.1: declared=image/png, detected=application/octet-stream
```

## Detalles

- El servicio corre como el usuario de sistema **`uploadguard`**, que crea el instalador: sin login,
  sin home, sin shell. Solo tiene lectura sobre el `.env`.
- La unidad se habilita por ruta absoluta (`systemctl enable /opt/uploadguard/uploadguard.service`),
  así el fichero se queda en `/opt`.
- Endurecimiento en la unidad: `ProtectSystem=strict`, `PrivateTmp`, sin capabilities. El servicio no
  escribe nada en disco.
- `-Djdk.httpclient.allowRestrictedHeaders=host` no es opcional; está en el `ExecStart`.
- El instalador **resuelve dónde está java** y lo escribe en el `ExecStart`. systemd no lee el `PATH`,
  necesita ruta absoluta. Prefiere `/usr/bin/java` cuando existe, porque en un JDK de paquete ese es
  el symlink de *alternatives* y sobrevive a las actualizaciones; si no, usa el del `PATH` o busca en
  `/usr/lib/jvm`, `/opt/java` y `/usr/local/bin`.
- Todo esto está probado en un contenedor con systemd real: `test/install/run.sh` (26 comprobaciones).
- `run.sh` lo ejecuta en primer plano sin tocar systemd, para probarlo suelto.
