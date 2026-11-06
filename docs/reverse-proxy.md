# Detrás de un reverse proxy

Condiciones que debe cumplir el hop situado delante del servicio, y diagnóstico de los fallos que se
producen cuando no se cumplen. Organizado como síntoma, causa y corrección.

## Config completa de nginx

Un único host público con enrutado por método: los verbos que pueden llevar cuerpo atraviesan
UploadGuard y el resto alcanza el backend directamente. No existe una segunda ruta que restringir.

```
                     ┌──► uploadguard ──► backend     (PUT, POST, PATCH)
cliente ──► nginx ───┤
                     └──► backend                     (GET, HEAD, DELETE, OPTIONS...)
```

Contexto `http`:

```nginx
upstream uploadguard_storage { server 10.0.0.2:3900; keepalive 16; }
upstream uploadguard_proxy   { server 10.0.0.2:8099; keepalive 16; }

# nginx no puede hacer match de location por método, así que la decisión es una variable
map $request_method $uploadguard_route {
    default  uploadguard_storage;
    PUT      uploadguard_proxy;
    POST     uploadguard_proxy;
    PATCH    uploadguard_proxy;
}
```

Contexto `server`:

```nginx
merge_slashes off;

location / {
    proxy_pass http://$uploadguard_route;

    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;

    proxy_request_buffering off;
    proxy_buffering off;

    client_body_timeout 60s;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
}
```

Los bloques `upstream` con nombre son necesarios: `proxy_pass` con una variable exige un `resolver`
para resolución DNS en tiempo de petición, salvo que el valor coincida con el nombre de un `upstream`
declarado, en cuyo caso nginx lo resuelve internamente. Sin ellos la respuesta es `502`.

Si un panel de administración genera el `location` a partir de sus propios ajustes, esa opción debe
desactivarse para el servicio. Dos `location /` en el mismo bloque `server` impiden el arranque de
nginx.

---

## `SignatureDoesNotMatch`

La firma SigV4 cubre el host, el path, la query y las cabeceras que declara firmadas. Si el hop de
delante toca cualquiera de esas, el backend rechaza. Tres causas, por frecuencia:

### El `Host` no llega

**Síntoma:** el backend responde `400` con `InvalidRequest: Host header required`. Desde `curl`
funciona; desde el navegador no.

**Causa:** `proxy_set_header Host $http_host;`. `$http_host` es la cabecera `Host` **cruda**, y con
**HTTP/2 el cliente no manda `Host`** — manda el pseudo-header `:authority`. Así que `$http_host` queda
vacío. Y nginx, ante un `proxy_set_header` con valor vacío, **omite la cabecera por completo**.

Por eso `curl` funciona: si habla HTTP/1.1, siempre manda `Host`.

**Arreglo:** `proxy_set_header Host $host;`. `$host` nunca está vacío — sale de `:authority`, de la
cabecera `Host`, o del `server_name` que hizo match.

**La pega:** `$host` normaliza, en minúsculas y sin puerto. Si firmas con un puerto no estándar,
comprueba este punto; para 443 y un dominio en minúsculas no hay diferencia.

### Las barras dobles se colapsan

**Síntoma:** las claves con `//` fallan, el resto funciona.

**Causa:** `merge_slashes` está **activo por defecto** en nginx. Colapsa `//` en `/` al parsear la
petición, antes de reenviar nada.

**Arreglo:** `merge_slashes off;`. Admite contexto `http` y `server`; ponlo en `server` para que solo
afecte a ese servicio.

### La URI llega decodificada

**Síntoma:** las claves con `%20`, tildes o ñ fallan.

**Causa:** `proxy_pass` **con path**. Con `proxy_pass http://backend/;` —esa barra final cuenta— nginx
sustituye el prefijo del `location` y pasa la URI **normalizada y decodificada**.

**Arreglo:** `proxy_pass` sin nada detrás del host. Así nginx pasa la URI tal como la mandó el cliente.

---

## El WAF bufferiza el cuerpo entero

**Síntoma:** `proxy_request_buffering off` está puesto, y aun así nada se valida hasta que la subida
termina. Un archivo de 60 MB se sube completo antes de recibir el `415`.

**Causa:** el conector **ModSecurity-nginx** lee el cuerpo entero en la fase *pre-access*, antes de que
corra el content handler. Eso hace `proxy_request_buffering off` inefectivo en cualquier `location`
donde ModSecurity esté activo.

**Alternativas que no corrigen el comportamiento:**

- `SecRequestBodyLimit` + `SecRequestBodyLimitAction ProcessPartial` — controlan cuánto **inspecciona**
  ModSecurity, no si nginx bufferiza.
- `SecRequestBodyAccess Off` — libmodsecurity copia el cuerpo en `appendRequestBody()` pase lo que
  pase; el flag solo se consulta después, en `processRequestBody()`
  ([issue #3022](https://github.com/owasp-modsecurity/ModSecurity/issues/3022)). Y encima desactivarlo
  llegó a saltarse las reglas de fase 2 y 4, dejando un bypass del CRS
  ([aviso del CRS](https://coreruleset.org/20210302/disabling-request-body-access-in-modsecurity-3-leads-to-complete-bypass/)).

Hay un PR abierto que añade una directiva `modsecurity_request_body on|off` justo para esto
([#391](https://github.com/owasp-modsecurity/ModSecurity-nginx/pull/391)), sin confirmación de estar
publicado.

**Arreglo:** desactivar ModSecurity para ese servicio. No hay forma granular hoy.

**Qué pierdes, en concreto:** el CRS detecta patrones de SQLi, XSS, RCE y path traversal. Contra un
endpoint S3 eso aporta poco: no hay aplicación detrás que interprete SQL ni plantillas, los únicos
parámetros son los `X-Amz-*` de la firma y la clave del objeto, y nada ocurre sin una firma válida. Es
además un sitio donde el CRS da falsos positivos con binarios.

**Qué no pierdes:** en BunkerWeb y similares, el rate limiting, las listas negras, el *bad behavior*,
el antibot y las cabeceras de seguridad son plugins **independientes** de ModSecurity y siguen activos.
Y el WAF sigue protegiendo tus otros dominios: esto se desactiva por servicio.

**Lo que ganas, además de la validación:** con el buffering activo hay hasta dos copias del cuerpo
fuera de memoria —la de nginx en `client_body_temp_path` y la de ModSecurity— así que diez subidas de
500 MB simultáneas son gigas de temporales que tumban **todo** el reverse proxy, no solo este servicio.
En streaming eso desaparece.

---

## El cliente no puede leer el rechazo (CORS)

**Síntoma:** el navegador reporta `ERR_FAILED` y un error de CORS en consola; el front no puede
distinguir "el archivo no es lo que dice" de "se cayó la red". El preflight sí pasó.

**Causa:** el `OPTIONS` del preflight lo contesta el backend, que tiene su CORS configurado. Pero el
`415` lo genera **UploadGuard**, y las cabeceras CORS del backend nunca llegan a esa respuesta.

**Corrección:** implementada en el servicio. `Http.reply` refleja el `Origin` de la petición en
`Access-Control-Allow-Origin`, con `Vary: Origin`, en las respuestas que emite. La exposición se limita
a esa línea de texto, ante un emisor que requirió autorización para alcanzar el endpoint.

---

## El rechazo tarda en llegar al navegador

**Síntoma:** la validación es instantánea —el log lo registra al segundo y la barra de progreso se
congela al principio— pero el navegador tarda diez o quince segundos en reportar el error.

**Causa, la que importa:** CORS. Si el navegador recibe una respuesta que la política CORS le prohíbe
leer, no se la entrega a la capa de `fetch`/`XHR`; la petición solo "termina" cuando falla del todo.
Con la cabecera puesta, el navegador procesa el `415` en cuanto llega y **aborta la subida él mismo**.

**La otra causa, menor:** rechazar a media petición en HTTP/1.1 es incómodo por diseño. Cuando dejamos
de leer, el buffer de recepción se llena, el cliente se queda bloqueado escribiendo y no procesa la
respuesta que ya tiene. Si cerráramos sin más, TCP manda un `RST` que **descarta** esa respuesta. El
*lingering close* de nginx existe para evitarlo: tras responder, sigue leyendo y tirando lo que el
cliente tenía en vuelo. Se ajusta con `lingering_timeout` y `lingering_time`, pero acortarlo trae de
vuelta el riesgo del reset.

Si el `415` alcanza al cliente correctamente, estos parámetros no requieren ajuste.

---

## Un rechazo grande puede llegar como `502`

**Síntoma:** un archivo pequeño que no coincide da `415`; uno grande da `502`.

**Causa:** ante un rechazo el servicio descarta hasta `MAX_DRAIN` (1 MiB) del cuerpo restante y cierra
la conexión. Si resta más, nginx continúa escribiendo, detecta el upstream cerrado y lo traduce a
`502`.

**El objeto no se guarda en ninguno de los dos casos.** Lo que se degrada es que el cliente vea el
motivo. Subir `MAX_DRAIN` en `UploadHandler` amplía el rango en que se ve el `415`, a cambio de leer y
tirar más bytes de una subida que ya sabemos mala.

---

## Multipart

`UploadPart` emplea el método `PUT`, de modo que con enrutado por método las partes atraviesan el
servicio sin configuración adicional.

Y una parte no es un archivo: si la primera trae cabecera de PNG y va declarada
`application/octet-stream`, se detecta `image/png`, no coinciden, y sale `415`.

Si habilitas multipart, saca las partes del enrutado por el query param que las identifica
(`partNumber`).

---

## Qué cubre la suite y qué no

`test/run-tests.sh --edge` levanta nginx delante con esta misma config y repasa las 71 pruebas con
firmas reales: claves con `//`, `%20`, tildes y ñ, 20 MB en streaming, rechazos por contenido.

**Fuera de cobertura:** el cliente de pruebas emplea HTTP/1.1. El fallo del `Host` vacío solo se
manifiesta bajo HTTP/2, por lo que la suite se completa sin errores con la configuración incorrecta.
Cualquier cambio en el tratamiento del `Host` requiere verificación con un agente de usuario real.
