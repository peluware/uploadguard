# UploadGuard

Proxy inverso que **rechaza una petición cuyo cuerpo no es lo que dice su `Content-Type`**, y reenvía
todo lo demás sin tocarlo.

Una sola responsabilidad: comprobar que la cabecera no miente. No decide qué tipos son aceptables, ni
qué métodos se permiten, ni qué hay detrás.

```
Cliente ──► UploadGuard ──► backend
```

Lee el principio del cuerpo, detecta el tipo real con Apache Tika y lo compara con el declarado. Si se
contradicen, responde `415` y el backend nunca ve la petición. Si no, la reenvía en streaming: un
cuerpo de cualquier tamaño pasa sin bufferizarse ni tocar disco.

Un único artefacto: `target/uploadguard.jar` (~1.8 MB, Tika incluido). No necesita más que una JVM.

## Construir y ejecutar

JDK 21+ para ejecutar. Maven solo para construir, nunca en el servidor.

```bash
mvn clean package
```

```bash
UPSTREAM_URL=http://127.0.0.1:3900 java -Djdk.httpclient.allowRestrictedHeaders=host -jar target/uploadguard.jar
```

O con `./run.sh` (Linux/macOS) o `run.cmd` (Windows), que construyen el jar si falta.

> `-Djdk.httpclient.allowRestrictedHeaders=host` **no es opcional**. Sin esa propiedad el `HttpClient`
> se niega a enviar el header `Host`, y un proxy que descarta el `Host` del cliente no es transparente.

| Variable | Obligatoria | Default | Descripción |
|---|---|---|---|
| `UPSTREAM_URL` | sí | — | URL base interna del backend, sin `/` final |
| `PORT` | no | `8080` | puerto de escucha |

Eso es toda la configuración.

## El contrato

| Situación | Respuesta |
|---|---|
| hay cuerpo y no contradice lo declarado | se reenvía |
| hay cuerpo y contradice lo declarado | `415`, sin llegar al backend |
| hay cuerpo sin `Content-Type` | se reenvía sin inspeccionar |
| no hay cuerpo | se reenvía sin inspeccionar |
| `Content-Length` y `Transfer-Encoding` a la vez | `400` |
| `Content-Length` malformado | `400` |
| error hablando con el backend | `502` |

Las respuestas de error las genera este servicio, así que las cabeceras CORS del backend no llegan a
ellas y un navegador no podría leer el estado. Por eso reflejan el `Origin` de la petición en
`Access-Control-Allow-Origin`: sin eso, el cliente ve un error de red en vez del motivo.

Se inspecciona **cualquier** petición que traiga cuerpo, sea `PUT`, `POST`, `PATCH` o lo que sea, y se
decide por las cabeceras y no por el método: un `DELETE` con cuerpo es legal, y qué verbos soporta el
backend no es asunto de este proxy. Acepta las dos formas de delimitar el cuerpo, `Content-Length` y
`Transfer-Encoding: chunked`, y reenvía con la misma con la que llegó.

Se inspecciona solo cuando hay **cuerpo** y hay **afirmación**. Sin `Content-Type` no hay nada que
contradecir, así que se reenvía: la cabecera es un *SHOULD* en RFC 9110 §8.3, no un *MUST*, y decidir
en su ausencia sería el proxy tomando una decisión que no le toca.

Un `Content-Length: 0` cuenta como "no hay cuerpo". Y si no viene ninguna de las dos cabeceras de
framing tampoco hay cuerpo: en HTTP/1.1 un cuerpo no tiene dónde acabar sin ellas, y lo que sigue a las
cabeceras es la siguiente petición de la conexión.

> Esa regla es **de HTTP/1.1**. En HTTP/2 el cuerpo lo delimitan los frames DATA, `Transfer-Encoding`
> está prohibido y `Content-Length` es opcional, así que ahí una petición puede traer cuerpo sin
> ninguna de las dos. Hoy es inalcanzable: el `HttpServer` del JDK solo habla HTTP/1.1, así que esto va
> detrás de un hop que termine HTTP/2 (tu `proxy_http_version 1.1;`). Si algún día se cambia de
> servidor, esta rama es la primera que hay que revisar.

Los dos `400` no son políticas, son mensajes que el proxy **no puede reenviar fielmente** porque no
sabe dónde acaba el cuerpo. Cuando llegan `Content-Length` y `Transfer-Encoding` juntas, la RFC 9112
§6.1 dice que la segunda manda y que el receptor debe rechazar o eliminar la primera antes de
reenviar; reenviar un framing ambiguo es la receta del request smuggling.

## Lo que no hace

- **No filtra tipos.** No hay lista blanca. Si quieres prohibir `image/svg+xml` —razonable, es XML y
  puede traer `<script>`— se decide donde se autoriza la petición. Aquí solo se comprueba que un SVG
  declarado sea de verdad un SVG.
- **No filtra métodos.** Un `GET` o un `DELETE` se reenvían y los juzga el backend.
- **No es un antivirus.** Mira el principio del cuerpo, no todo: un archivo con cabecera PNG válida y
  basura detrás pasa. Lo que evita es que te cuelen un ejecutable declarado como imagen.
- **No mira dentro de los contenedores.** Tika sabe hacerlo, pero con `tika-parsers`, y el directorio
  central de un zip está al final del archivo: inspeccionarlo exigiría tenerlo completo, que es justo
  lo que un proxy en streaming no puede hacer.

## Estructura

| Clase | Responsabilidad |
|---|---|
| `UploadGuard` | punto de entrada: configuración y arranque del servidor |
| `Settings` | la configuración, leída del entorno una sola vez |
| `ContentInspector` | tipos: alias, detección con Tika y la regla de contradicción |
| `UploadHandler` | qué se inspecciona y con qué código se responde |
| `Upstream` | reenvío: headers, URI en crudo, streaming y copia de la respuesta |
| `Http` | responder, drenar un cuerpo rechazado, normalizar `Content-Type` |

`ContentInspector` no hace E/S, así que las reglas se prueban solas: `mvn test` corre 42 casos.

## La regla de contradicción

Rechazar **solo con evidencia**. Una detección que ve únicamente el principio del cuerpo puede caer en
una categoría más amplia que la declarada, y eso no es una contradicción:

- un **docx** se ve como `application/zip` o `application/x-tika-ooxml`;
- **JSON** y **CSV** se ven como `text/plain`;
- **SVG** se ve como `application/xml`.

Se aceptan, porque la jerarquía de Tika dice que lo declarado es una especialización de lo detectado.
Rechazarlos sería un falso positivo por la resolución del detector, no por deshonestidad.

Con **una** excepción: `application/octet-stream` nunca cuenta como categoría más amplia. Todos los
tipos descienden de él en el registro de Tika, así que aceptarlo dejaría pasar cualquier binario bajo
cualquier tipo declarado. Cuando Tika cae ahí es que ningún magic coincidió, y eso sí contradice a
cualquier tipo con firma propia.

La tolerancia va **en una sola dirección**: se acepta que la detección se quede corta, no que la
declaración lo haga. Declarar menos de lo que el archivo es se rechaza, aunque sea verdad:
`application/zip` con un `.apk` dentro da `415`, y lo mismo `application/octet-stream` con cualquier
cosa reconocible. Es deliberado — el contrato es que el `Content-Type` sea el tipo **real**, no un
ancestro verdadero de él.

De ahí una regla para quien emite las peticiones: **no declarar tipos vagos.** Si esperas un APK,
declara `application/vnd.android.package-archive`; si esperas un zip, `application/zip`. Un tipo
amplio no hace la comprobación más permisiva, la hace fallar.

Efecto colateral en la otra dirección: cualquier zip cuela declarado como docx, porque desde un prefijo
son indistinguibles. Es el precio de no rechazar un docx legítimo.

**Alias:** solo los que resuelve el `MediaTypeRegistry` de Tika (`application/x-pdf` →
`application/pdf`). Las grafías viejas que el registro no conoce, como `image/jpg`, se dejan tal cual:
mantener una tabla a mano sería adivinar cuáles importan. Si algún cliente usara una, el rechazo lo
dice en el log (`declared=image/jpg, detected=image/jpeg`).

## Streaming

El cuerpo se reenvía sin bufferizarse, y no se copia el prefijo a mano: Tika marca el stream, lee y
hace reset, así que se reenvía el mismo `BufferedInputStream` que se inspeccionó, dimensionado con el
`Content-Length` para no reservar 64 KB por un archivo de 3 KB.

Tika lee 64 KB en cada detección — su propio `MimeTypes.getMinLength()`, no un número nuestro. No lee
"lo que va necesitando": llena el prefijo completo y después hace reset, aunque un PNG se resuelva con
ocho bytes.

La respuesta del backend también va en streaming. Normalmente es vacía o unos cientos de bytes, pero
leerla a un array sería una asignación sin techo decidida por el otro extremo.

Cuando se rechaza un cuerpo se descarta hasta 1 MiB de lo que queda antes de responder: un servidor
HTTP/1.1 que contesta a media petición tiene que cerrar la conexión, y muchos clientes reportan ese
reset en vez del código de estado.

Los timeouts del cliente los pone el reverse proxy. A propósito no se usa `HttpRequest.timeout`: es un
plazo para el intercambio completo y mataría los cuerpos grandes.

## Desplegar

| Dónde | Guía |
|---|---|
| Linux, servicio systemd | [deploy/linux/](deploy/linux/README.md) |
| Windows, tarea programada | [deploy/windows/](deploy/windows/README.md) |

Todo el servicio vive en un único directorio: el jar, la configuración y los scripts. El instalador se
ejecuta desde ahí dentro. Es idempotente y hay desinstalador.

**Logging:** no hace falta nada externo. El código usa `System.Logger`, que no es solo fachada: sin
proveedor de `LoggerFinder`, el JDK usa `java.util.logging`, que escribe a stderr y de ahí a journald.
La única dependencia de logging es `slf4j-jdk14` (10 KB), y es por Tika, que registra con SLF4J y sin
proveedor manda todo a un NOP.

> No usar `slf4j-jdk-platform-logging`: registra un `System.LoggerFinder` y enruta al revés,
> `System.Logger` → SLF4J, con lo que los logs de **esta** aplicación desaparecen.

## Pruebas

Suite de extremo a extremo con Docker. Necesita Docker y Python.

```bash
test/run-tests.sh          # Windows: test\run-tests.cmd
```

Levanta un backend S3-compatible real y el jar, y sube con `curl` usando URLs presignadas de verdad
(firmadas con boto3, el firmante de referencia). Cubre claves con espacios, tildes, ñ, `//` y
caracteres reservados, subida de 20 MB en streaming, un docx real que solo se detecta como
`x-tika-ooxml`, rechazos por contenido que contradice la cabecera, validación bajo verbos distintos de
`PUT`, y los códigos de protocolo. Con `--keep` deja el stack levantado.

---

# Caso de uso: subidas directas con presigned URLs

Fue el motivo original del proxy. Nada de esto está en el código; es configuración de alrededor.

Los clientes suben archivos directo a un storage S3-compatible con presigned PUT URLs. El
`Content-Type` va firmado en la URL, pero el storage solo verifica que la cabecera coincida con la
firma; no inspecciona el contenido. Un cliente puede subir cualquier cosa declarando `image/png`.

```
Cliente ──► reverse proxy ──► upload.<dominio> ──► UploadGuard ──► backend S3 (intranet)
                         └──► s3.<dominio> (GET y demás) ─────────► backend S3
```

## Por qué no hay lista de tipos

El `Content-Type` viaja **dentro de la firma**, así que el cliente no lo elige: lo elige quien emite las
presigned URLs, y el storage lo hace cumplir rechazando con `SignatureDoesNotMatch` cualquier otro. Una
lista aquí sería una segunda copia de esa política, y el día que el backend firme un tipo nuevo sin
actualizar este servicio, rechazaríamos subidas que el backend autorizó.

**Dónde se decide qué se puede subir: en tu backend, al firmar.** Ahí se bloquea `image/svg+xml` si no
lo quieres.

## Invariantes de la firma

Lo que llega al backend tiene que ser idéntico a lo que se firmó. UploadGuard preserva:

- el `Host` original, **con puerto** (de ahí la propiedad del sistema de más arriba);
- el path y la query **en crudo**, sin decodificar ni normalizar: `%20`, `%C3%B1`, `//`, `+`, y todos
  los `X-Amz-*` pasan tal cual;
- todos los headers firmados, incluido el `Content-Type` **exactamente** como lo mandó el cliente. La
  normalización para comparar tipos se hace sobre una copia; añadir un `; charset=` al header real
  rompería la firma.

Las presigned URLs usan `UNSIGNED-PAYLOAD`, así que leer el principio del cuerpo no invalida nada.

El backend debe generar las URLs con `forcePathStyle(true)`, con el host del subdominio de subida y con
la misma región configurada en el storage.

## Cerrar el paso por el otro subdominio

**Esto es lo que puede dejar el proxy en decorativo.** Si tu backend firma alguna vez un PUT contra el
host `s3.`, o si el storage acepta PUT por ese servicio, el cliente sube sin pasar por aquí:

1. las presigned de subida se firman **solo** con el host `upload.`;
2. en el servicio `s3.` del reverse proxy, el método `PUT` cerrado.

Y como este proxy no filtra métodos, si quieres que por `upload.` solo quepan `PUT` y `OPTIONS`, eso va
también en el reverse proxy.

## Detrás de un reverse proxy

Lo que el hop de delante no puede hacer, o la firma se rompe:

| Qué | Por qué |
|---|---|
| `proxy_set_header Host $host` | con `$http_host`, en HTTP/2 el cliente manda `:authority` y no `Host`, la variable queda vacía, y nginx omite una cabecera vacía: el backend recibe la petición **sin `Host`** |
| `merge_slashes off` | nginx colapsa `//` en `/` al parsear, antes de reenviar, y una clave con `//` llega mutilada |
| `proxy_pass` **sin path detrás** | con un path, incluso una sola `/`, nginx sustituye el prefijo y pasa la URI normalizada y decodificada |
| `proxy_request_buffering off` | si no, nada se valida hasta que la subida termina |
| WAF desactivado en ese servicio | el conector de ModSecurity bufferiza el cuerpo entero pase lo que pase, y el CRS da falsos positivos con binarios |

Añadir cabeceras es inofensivo: una firma solo cubre las que declara como firmadas.

**→ [docs/reverse-proxy.md](docs/reverse-proxy.md)** tiene la config completa de nginx y cada uno de
estos puntos como síntoma → causa → arreglo, además de lo que se aprendió en producción: por qué
`SecRequestBodyAccess Off` no sirve, por qué un rechazo grande puede llegar como `502`, por qué el
navegador tardaba en enterarse, y qué pasa con el multipart.

## Limitaciones

- No sirve con AWS S3 real: AWS enruta por `Host`. Solo backends self-hosted.
- El multipart queda fuera: una parte individual no es un archivo completo, así que su principio no
  dice nada. Si lo habilitas, esas subidas no se validan.
- Complementar al **servir** los archivos con `X-Content-Type-Options: nosniff` y
  `Content-Disposition: attachment`.
