"use strict"

/**
 * Qué sabe hacer esta versión del agente.
 *
 * La lista se manda en cada latido y el servidor la usa para no encolarle a un
 * agente viejo un tipo de trabajo que no entiende. Por eso agregar una capacidad
 * nueva es seguro: los agentes que no se actualizaron simplemente no la declaran
 * y nunca la reciben.
 *
 * PARA SUMAR UNA CAPACIDAD:
 *   1. agregar el nombre acá,
 *   2. crear el handler en src/jobs/<nombre>.js,
 *   3. registrarlo en src/jobs/index.js,
 *   4. agregar el tipo en lib/agent/types.ts del lado de la web.
 * Nada de esto toca la base de datos ni la API.
 */

const VERSION = require("../package.json").version

const CAPABILITIES = [
  "print.raw",
  "printer.list",
  "cashdrawer.open",
  "agent.ping",
]

module.exports = { VERSION, CAPABILITIES }
