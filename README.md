# UploadGuard

Proxy inverso HTTP que verifica la correspondencia entre el `Content-Type` declarado de una petición y
el tipo real de su cuerpo, determinado a partir de sus magic bytes. Las peticiones cuyo contenido
contradice la declaración se rechazan con `415` y no alcanzan el backend; el resto se retransmite sin
modificaciones.

```
cliente ──► uploadguard ──► backend
```

Implementado sobre `com.sun.net.httpserver.HttpServer` y `java.net.http.HttpClient` con hilos
virtuales. La detección de tipos usa `tika-core`. Requiere JDK 21 o superior en ejecución.

## Comportamiento

### Criterio de inspección

Se inspecciona toda petición que cumpla dos condiciones: declara un cuerpo y declara un
`Content-Type`. El método no interviene en la decisión. Un `DELETE` con cuerpo se inspecciona; un
`GET` sin cuerpo no. Qué verbos admite el backend es una cuestión del backend.

En ausencia de `Content-Type` la petición se retransmite sin inspeccionar: no hay declaración que
contradecir. La cabecera es un *SHOULD* en RFC 9110 §8.3, no un *MUST*.

### Delimitación del cuerpo

Se admiten las dos formas de delimitación de HTTP/1.1, `Content-Length` y `Transfer-Encoding:
chunked`, y la petición se retransmite con la misma con la que llegó.

Un `Content-Length: 0` equivale a ausencia de cuerpo. La ausencia de ambas cabeceras también: en
HTTP/1.1 un cuerpo de petición carece de delimitación sin ellas, y los octetos que siguen a las
cabeceras pertenecen a la siguiente petición de la conexión (RFC 9112 §6.3).

La presencia simultánea de `Content-Length` y `Transfer-Encoding` se rechaza con `400`. RFC 9112 §6.1
establece que la segunda prevalece y que el receptor debe rechazar el mensaje o eliminar la primera
antes de retransmitirlo; retransmitir una delimitación ambigua habilita *request smuggling*.

> Este criterio de delimitación es específico de HTTP/1.1. En HTTP/2 el cuerpo lo delimitan los frames
> DATA, `Transfer-Encoding` está prohibido y `Content-Length` es opcional, de modo que una petición
> puede llevar cuerpo sin ninguna de las dos cabeceras. El `HttpServer` del JDK solo implementa
> HTTP/1.1, por lo que el servicio debe situarse detrás de un hop que termine HTTP/2.

### Códigos de respuesta

| Condición | Código |
|---|---|
| El contenido no contradice la declaración | el del backend |
| El contenido contradice la declaración | `415` |
| `Content-Length` y `Transfer-Encoding` simultáneos | `400` |
| `Content-Length` malformado | `400` |
| Ausencia de cabecera `Host` | `400` |
| Fallo de comunicación con el backend | `502` |

Las respuestas generadas por el servicio reflejan el `Origin` de la petición en
`Access-Control-Allow-Origin`, con `Vary: Origin`. Sin ello un agente de usuario no puede leer el
código de estado de un rechazo, ya que las cabeceras CORS del backend no forman parte de una respuesta
que el backend no emitió.

## Correspondencia de tipos

La detección opera sobre un prefijo del cuerpo, no sobre su totalidad, por lo que puede resolver a un
tipo más general que el declarado sin que ello constituya una contradicción. El criterio es rechazar
únicamente ante evidencia de discrepancia.

Una declaración se acepta cuando coincide con lo detectado, o cuando el registro de tipos de Tika la
clasifica como especialización de lo detectado:

| Declarado | Detectado | Resultado |
|---|---|---|
| `image/png` | `image/png` | aceptado, coincidencia |
| `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | `application/x-tika-ooxml` | aceptado, especialización |
| `application/json` | `text/plain` | aceptado, especialización |
| `image/png` | `application/pdf` | rechazado, linajes disjuntos |
| `image/png` | `application/octet-stream` | rechazado, ver excepción |

`application/octet-stream` queda excluido como tipo general. Todos los tipos descienden de él en el
registro de Tika, de modo que admitirlo como ancestro aceptaría cualquier contenido bajo cualquier
declaración. Su aparición indica que ningún patrón coincidió, lo que sí contradice a cualquier tipo
con firma propia.

La tolerancia es unidireccional: se admite que la detección resulte menos precisa que la declaración,
no al contrario. Declarar un ancestro verdadero del tipo real se rechaza — `application/zip` con un
`.apk` como contenido produce `415`. El contrato es que el `Content-Type` sea el tipo real, no un
supertipo correcto.

De ello se deriva un requisito para el emisor de las peticiones: declarar el tipo específico. Un tipo
general no relaja la comprobación, la hace fallar.

Consecuencia en el sentido contrario: cualquier archivo ZIP se acepta declarado como OOXML, dado que
ambos son indistinguibles desde un prefijo. Es el coste de no rechazar documentos legítimos.

### Alias

Se resuelven los alias conocidos por el `MediaTypeRegistry` de Tika (`application/x-pdf` →
`application/pdf`). Las grafías no registradas, como `image/jpg`, no se normalizan. El registro de
rechazo incluye ambos tipos, lo que permite identificar el caso:

```
Rejected PUT /ruta/archivo.jpg over HTTP/1.1: declared=image/jpg, detected=image/jpeg
```

## Características de ejecución

El cuerpo se retransmite en flujo. La detección marca el stream, lee su prefijo y lo restituye a la
posición inicial, de modo que se retransmite el mismo `BufferedInputStream` que se inspeccionó. El
buffer se dimensiona con `min(Content-Length, 64 KiB)`.

El prefijo inspeccionado es de 64 KiB, valor que toma de `MimeTypes.getMinLength()` de Tika. Se lee
íntegro en cada detección, con independencia de cuántos octetos requiera el formato concreto.

La respuesta del backend también se retransmite en flujo. Acumularla en memoria constituiría una
asignación sin cota determinada por el otro extremo.

Ante un rechazo se descarta hasta 1 MiB del resto del cuerpo antes de responder (`MAX_DRAIN` en
`UploadHandler`). Un servidor HTTP/1.1 que responde a mitad de petición debe cerrar la conexión, y el
cierre con datos pendientes de lectura produce un `RST` que descarta la respuesta no leída. Superado
ese umbral el cliente puede recibir un `502` del reverse proxy en lugar del `415`; en ambos casos la
petición no alcanza el backend.

No se establece `HttpRequest.timeout`: su semántica es un plazo para el intercambio completo e
interrumpiría transferencias grandes legítimas. Los plazos del lado cliente corresponden al reverse
proxy. El único plazo configurado es el de conexión TCP con el backend, de 5 s.

El servicio no escribe en disco.

## Configuración

| Variable | Obligatoria | Valor por defecto | Descripción |
|---|---|---|---|
| `UPSTREAM_URL` | sí | — | URL base del backend, sin barra final |
| `PORT` | no | `8080` | puerto de escucha |

No existe lista de tipos admitidos ni de métodos admitidos. Ambas decisiones corresponden a quien
autoriza las peticiones o al reverse proxy.

## Construcción y ejecución

Maven se requiere para construir, no para ejecutar.

```bash
mvn clean package
```

Produce `target/uploadguard.jar`, de unos 1,8 MB, con Tika incluido.

```bash
UPSTREAM_URL=http://127.0.0.1:3900 \
  java -Djdk.httpclient.allowRestrictedHeaders=host -jar target/uploadguard.jar
```

`-Djdk.httpclient.allowRestrictedHeaders=host` es obligatorio. Sin esa propiedad el `HttpClient`
rechaza establecer la cabecera `Host`, que debe retransmitirse sin alterar.

### Registro

El código emite a través de `System.Logger`. En ausencia de un proveedor de `LoggerFinder`, el JDK
utiliza `java.util.logging`, que escribe a stderr y, bajo systemd, a journald. No se requiere ninguna
biblioteca adicional.

La única dependencia de registro es `slf4j-jdk14`, necesaria porque Tika emite a través de SLF4J, que
sin proveedor descarta toda su salida.

> No debe emplearse `slf4j-jdk-platform-logging` con ese fin. Ese artefacto registra un
> `System.LoggerFinder` y encamina en sentido inverso, de `System.Logger` hacia SLF4J, lo que silencia
> la salida de esta aplicación.

## Despliegue

| Plataforma | Guía |
|---|---|
| Linux, servicio systemd | [deploy/linux/](deploy/linux/README.md) |
| Windows, tarea programada | [deploy/windows/](deploy/windows/README.md) |

El servicio reside en un único directorio junto a su configuración y sus scripts. El instalador se
ejecuta desde ese directorio, es idempotente y dispone de desinstalador.

## Pruebas

```bash
mvn test                   # 42 casos sobre las reglas de correspondencia
test/run-tests.sh          # extremo a extremo; Windows: test\run-tests.cmd
test/run-tests.sh --edge   # idem, con nginx delante enrutando por método
test/install/run.sh        # instalador contra systemd en contenedor
```

La suite de extremo a extremo levanta un backend S3-compatible y el jar en Docker, y transfiere con
`curl` empleando URLs presignadas generadas con boto3. Cubre claves con espacios, caracteres
acentuados, `//` y caracteres reservados; transferencia de 20 MB en flujo; un documento OOXML real;
rechazos por discrepancia de contenido bajo distintos verbos; y los códigos de delimitación. Requiere
Docker y Python.

## Arquitectura

| Clase | Responsabilidad |
|---|---|
| `UploadGuard` | lectura de configuración y arranque del servidor |
| `Settings` | configuración, leída del entorno al iniciar |
| `ContentInspector` | detección, alias y regla de correspondencia |
| `UploadHandler` | criterio de inspección y códigos de respuesta |
| `Upstream` | retransmisión: cabeceras, URI sin normalizar, flujo y copia de la respuesta |
| `Http` | emisión de respuestas, descarte de cuerpos rechazados, normalización de `Content-Type` |

`ContentInspector` no realiza E/S, lo que permite verificar las reglas de correspondencia de forma
aislada.

## Licencia

Apache License 2.0. Ver [LICENSE](LICENSE) y [NOTICE](NOTICE).

Las dependencias empaquetadas en el jar son permisivas: Apache-2.0 (`tika-core`, `commons-io`,
`commons-secure-xml`), BSD-2-Clause (`commonmark`) y MIT (`slf4j`). El artefacto incluye la licencia y
un `META-INF/NOTICE` con las atribuciones fusionadas de los componentes Apache.

## Limitaciones

- La verificación cubre el prefijo del cuerpo, no su totalidad. Un archivo con cabecera válida seguida
  de contenido arbitrario se acepta. El alcance es impedir la declaración falsa de tipo, no el análisis
  de contenido.
- No se inspecciona el interior de contenedores. Hacerlo requiere `tika-parsers` y el archivo completo:
  el directorio central de un ZIP reside al final del archivo.
- Las transferencias multiparte quedan fuera del alcance. Una parte individual no constituye un archivo
  completo, por lo que su prefijo no es significativo.
- Al servir los archivos almacenados conviene emplear `X-Content-Type-Options: nosniff` y
  `Content-Disposition: attachment`.

---

# Integración con URLs presignadas S3

Caso de uso de origen. La configuración descrita aquí es externa al servicio.

Los clientes transfieren archivos directamente a un almacenamiento S3-compatible mediante URLs
presignadas. El `Content-Type` forma parte de la firma, pero el almacenamiento solo verifica que la
cabecera coincida con lo firmado; no examina el contenido. Un cliente puede transferir contenido
arbitrario declarando `image/png`.

## Invariantes de la firma

Lo que alcanza al backend debe ser idéntico a lo que se firmó. El servicio preserva:

- la cabecera `Host` original, incluido el puerto;
- el path y la query sin decodificar ni normalizar: `%20`, `%C3%B1`, `//`, `+` y los parámetros
  `X-Amz-*` se retransmiten literalmente;
- las cabeceras firmadas, incluido el `Content-Type` exacto. La normalización necesaria para comparar
  tipos opera sobre una copia; añadir `; charset=` a la cabecera real invalidaría la firma.

Las URLs presignadas emplean `UNSIGNED-PAYLOAD`, por lo que leer el prefijo del cuerpo no afecta a la
verificación.

El emisor debe generar las URLs con `forcePathStyle(true)`, con el host público del servicio y con la
región configurada en el almacenamiento.

## Política de tipos

El `Content-Type` forma parte de la firma, de modo que no lo elige el cliente sino el emisor de las
URLs, y el almacenamiento lo hace cumplir rechazando con `SignatureDoesNotMatch` cualquier otro valor.

Una lista de tipos en este servicio duplicaría esa política y divergiría de ella: al incorporar un
tipo nuevo en el emisor, el proxy rechazaría transferencias autorizadas. La restricción de tipos
corresponde al emisor de las URLs.

## Aislamiento de rutas

Si existe una ruta alternativa al almacenamiento que no atraviese el proxy, la verificación es
evitable. Con un único host público y enrutado por método en el reverse proxy el problema no se
presenta. Con hosts separados se requiere que las URLs de escritura se firmen exclusivamente con el
host que atraviesa el proxy, y que el método `PUT` esté cerrado en el otro.

## Requisitos del reverse proxy

| Directiva | Motivo |
|---|---|
| `proxy_set_header Host $host` | `$http_host` queda vacío bajo HTTP/2, donde el cliente envía `:authority` en lugar de `Host`; nginx omite las cabeceras de valor vacío y el backend recibe la petición sin `Host` |
| `merge_slashes off` | nginx colapsa `//` en `/` durante el análisis, antes de retransmitir |
| `proxy_pass` sin path | con un path, incluida una sola barra, nginx sustituye el prefijo del `location` y retransmite la URI normalizada y decodificada |
| `proxy_request_buffering off` | en caso contrario la verificación no ocurre hasta completarse la transferencia |
| ModSecurity desactivado en el servicio | su conector para nginx acumula el cuerpo íntegro con independencia de esa directiva |

La adición de cabeceras no afecta a la firma, que cubre únicamente las que declara firmadas.

[docs/reverse-proxy.md](docs/reverse-proxy.md) contiene la configuración completa de nginx y el
diagnóstico de cada una de estas condiciones.
