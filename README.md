# UcoBot Agent

Programa que corre en la PC del local y ejecuta lo que UcoBot no puede hacer desde
el navegador: imprimir un ticket sin el diálogo de Chrome, cortar el papel, abrir
la gaveta de dinero.

## Por qué existe

`window.print()` **siempre** abre el diálogo de impresión. No es un bug ni una
configuración: es una decisión de seguridad del navegador y no tiene vuelta. La
única forma de imprimir directo es que haya un programa nativo en la máquina.

## Cómo se comunica (y por qué no es un servidor en localhost)

La opción obvia era que el agente levantara un servidor en `http://localhost:9100`
y la web le pegara ahí. Se descartó:

- **Private Network Access**: Chrome viene apretando las llamadas desde un origen
  público hacia la red privada. Exige preflight con headers especiales y es un
  blanco móvil que puede romper todas las cajas con una actualización del navegador.
- Sólo funciona si el navegador y la impresora están en la **misma máquina**.
- No resuelve lo más valioso: imprimir cuando **nadie tiene la pestaña abierta**.

En vez de eso el agente **sale hacia afuera** y trabaja por cola:

```
UcoBot (web o servidor)  ──INSERT──▶  agent_jobs (Supabase)
                                          │
                                          │ trigger → Realtime "hay trabajo"
                                          ▼
                                   UcoBot Agent (esta PC)
                                          │ HTTPS autenticado: trae el trabajo
                                          ▼
                                     Impresora térmica
                                          │
                                          └──▶ escribe el resultado en la fila
```

El mensaje de Realtime no lleva datos (`{ring:1}`): sólo despierta al agente, que
después va a buscar el trabajo real por HTTPS con su token. Si el WebSocket se
cae, una consulta de reserva cada minuto lo cubre.

## Arquitectura

```
src/
  index.js          bucle principal: latido, timbre, ejecución de trabajos
  installer.js      el .exe se copia solo, se agenda al inicio y se relanza
  config.js         config en disco (ProgramData\UcoBot\agent.json)
  api.js            cliente HTTP contra UcoBot
  doorbell.js       WebSocket a Supabase Realtime
  setup-server.js   pantalla local en http://localhost:17845
  capabilities.js   qué sabe hacer esta versión
  jobs/
    index.js        REGISTRO DE HANDLERS ← acá se extiende el agente
    print-raw.js
  printers/
    windows-raw.js  spooler RAW vía PowerShell + winspool.drv
    tcp.js          impresoras de red por el puerto 9100
    list.js         inventario de impresoras del sistema
```

### Se instala solo

No hay instalador aparte. Al correr el `.exe` desde cualquier carpeta se copia a
`%LOCALAPPDATA%\UcoBot`, deja un acceso directo en Inicio y otro en el escritorio,
lanza la copia instalada suelta de la consola y sale. Sin permisos de
administrador.

Es a propósito: pedirle a un comerciante que le haga botón derecho a un `.ps1` y
elija "Ejecutar con PowerShell" es un paso que no entiende y que Windows encima
suele bloquear con un cartel rojo. Doble click tiene que alcanzar.

Detalle importante: un ejecutable de pkg es una aplicación de consola, así que al
hacerle doble click aparece una ventana negra y el proceso vive dentro de ella —
cerrarla mata el agente. Por eso siempre se relanza con `detached`, y la ventana
queda huérfana y se puede cerrar sin consecuencias.

Para desinstalar: `UcoBotAgent.exe --uninstall`.

### El agente es un caño, no un cerebro

El ticket lo arma **la web** (`lib/printing/escpos.ts` del repo de UcoBot) y acá
llegan bytes ya listos. Es la decisión de diseño más importante del proyecto: si
el agente armara el ticket, cada cambio de diseño obligaría a reinstalar el
programa en la PC de cada cliente. Así, un cambio de layout es un deploy a Vercel.

### Sumar una capacidad nueva

1. Agregar el nombre en `src/capabilities.js`.
2. Crear el handler en `src/jobs/` (recibe el payload, devuelve un resultado o
   tira un `Error` con un mensaje entendible: ese texto se le muestra al dueño).
3. Registrarlo en `src/jobs/index.js`.
4. Agregar el tipo en `lib/agent/types.ts` del repo de UcoBot.

No hay que tocar la base de datos ni la API: `agent_jobs.type` es texto libre y
`payload` es JSON. Un agente viejo que no declara la capacidad nueva simplemente
nunca la recibe.

Candidatos naturales: leer una balanza por puerto serie, exportar un backup de la
caja, controlar un visor de cliente, disparar un comando en un lector de códigos.

## Dónde vive este código

La fuente es el monorepo privado de UcoBot, en `agent/`. Este repo público es un
**espejo automático**: existe sólo porque los assets de Releases de un repo
privado no se pueden descargar sin estar logueado, y el instalador tiene que
bajarlo cualquier cliente con un click.

No edites nada acá — se pisa en el próximo push. Los cambios van en `agent/` del
monorepo, y el workflow `sync-agent.yml` los espeja.

## Desarrollo

```bash
npm install
UCOBOT_SERVER_URL=http://localhost:3000 npm start
```

Abre `http://localhost:17845` para vincular contra el entorno que apuntes.

## Compilar el .exe

```bash
npm run build
```

`pkg` empaqueta Node adentro del ejecutable: el cliente no instala nada previo.
El resultado queda en `dist/UcoBotAgent.exe`.

## Publicar una versión

```bash
npm version patch
git push --follow-tags
```

El workflow de GitHub Actions compila, arma el ZIP con los scripts de instalación
y lo publica en Releases. El botón de descarga del dashboard apunta a
`/releases/latest`, así que no hay que tocar nada en la web.
