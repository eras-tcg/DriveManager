# DriveManager: guía para agentes

Backend de ERAS TCG y motor del juego. Hay dos módulos en este repo:

| Módulo | Qué es | Build |
|---|---|---|
| `eras-engine/` | **El juego**: reglas, efectos, combate, sala, bots y lectura de cartas. Kotlin Multiplatform (JVM + JS), en `src/commonMain` con paquetes `com.drive.drive_manager.simulator.*`. Sale como JAR para el servidor y como paquete npm `@eras/engine` para eras-web. | Gradle |
| `drive_manager/` | **El servidor**: Spring Boot 4 en Java, con STOMP `/ws`, REST `/api/*`, Mongo, misiones, ranking e historial. Depende de `com.eras:eras-engine-jvm:0.1.0-SNAPSHOT`, que toma de `~/.m2`. | Maven |

Documentación de detalle, que conviene leer antes de tocar el motor:
- `eras-engine/README.md`: `LocalMatch` (la partida offline en el navegador), el contrato de mensajes, la lógica de sala compartida (`RoomSeats`, `DeckChoice`, `RoomBots`) y las partidas offline y sus recompensas.
- `drive_manager/docs/kotlin-conversion/README.md`: cómo se migró el motor a Kotlin, sus reglas (`@JvmRecord`, `data object`, sin `internal` en lo que llama Java, mantener la semántica de Java) y dónde vive cada cosa.

## Compilar y probar

```bash
cd eras-engine && ./gradlew build publishToMavenLocal jsSmoke   # JVM + Node + paquete npm + una partida en Node
cd drive_manager && mvn -q clean test-compile
mvn test -Dtest='com.drive.drive_manager.simulator.**' -Dsurefire.failIfNoSpecifiedTests=false   # ~688 tests, 18 omitidos
mvn test                                                          # todo el servidor
```

- **Después de cambiar el motor, siempre corre `publishToMavenLocal` antes de compilar drive_manager**: Maven toma el JAR de `~/.m2`, no del código fuente.
- Usa siempre `mvn clean`: clases viejas en `target/` pueden ocultar errores.
- No corras dos builds de Gradle o Maven al mismo tiempo.

## Reglas que no se rompen

- **Hashes de referencia** (`json/SimulatorJsonTest`, huellas de `MatchReplay`, constantes de `SeededBotGameTest` y `LocalMatchTest`): nunca los edites para que un test pase. Si un cambio de comportamiento es intencional y los cambia, justifícalo con el diff del JSON.
- **El motor debe dar lo mismo en la JVM y en JS.** `LocalMatchParityTest` exige que los mensajes de `LocalMatch` sean idénticos byte a byte a los del servidor, y `LocalMatchTest`/`SeededBotGameTest` corren en los dos. Usa `Platform.*` (expect/actual) para todo lo que dependa de la plataforma; `CoreBoundaryTest` prohíbe que el núcleo importe Spring, Jackson, reflexión o clases del servidor.
- Las cartas viven en `eras-engine/cards/` (una sola copia). El paquete `card-bundle.json` se genera en el build.
- Si cambias el motor, actualiza eras-web: en ese repo, `npm run engine:update` copia `eras-engine/build/dist/js/productionLibrary` a `vendor/eras-engine`.

## Tests inestables conocidos (no son regresiones)

- `BotPlayTest.theLookaheadBotBeatsTheHeuristicOne`: depende de tiempos y falla si la máquina está cargada.
- `BotPlayTest.aCopyOfTheRoomPlaysApartFromTheRoom`: falla en ~1 % de las ejecuciones, también en el código anterior a la migración.
- `DriveCatchUpServiceTest`: depende de tiempos.
- `DriveManagerApplicationTests.contextLoads`: falla en local sin MongoDB; en el CI pasa.

Si uno falla, córrelo solo antes de concluir algo.

## Despliegue

- Rama de trabajo → PR a **`develop`** (despliega staging) → PR `develop` → **`main`** (despliega producción). Producción **espera la aprobación del dueño** en GitHub; un agente no puede aprobar.
- El CI (`.github/workflows/ci-cd.yml`) tiene estos jobs:
  - `engine`: motor, paquete npm y partida en Node;
  - `test`: compila el motor y corre los tests del servidor con un MongoDB desechable;
  - `build`: imagen Docker;
  - `deploy-production`: usa `deploy-aws.yml` del repo infra.
- `drive_manager/Dockerfile` construye desde la raíz del repo en tres etapas: motor (Gradle), servidor (Maven) y JRE. Si el JAR del motor necesita archivos nuevos (como pasó con `st*-cards.json`), hay que copiarlos en la etapa `engine`.
- GitHub despliega a `main` de a un run por vez: un despliegue sin aprobar bloquea los siguientes.

## Decisiones del dueño (no cambiarlas sin preguntar)

- Las partidas contra el bot (Modo solo, online y offline) cuentan para misiones y progreso. La sala de pruebas (`room.isEditor()`) no. "Tu primera partida" sigue siendo solo contra jugadores.
- Partidas offline (`POST /api/simulator/offline-games`, paquete `simulator/offline`):
  - el servidor las vuelve a jugar con `LocalMatch` en la JVM y las acredita una sola vez;
  - **no hay tope diario**; el servidor solo responde `retry` si está muy cargado (200 repeticiones al día por usuario);
  - cuentan si tienen menos de 30 días;
  - el mazo solo tiene que ser legal (`DeckRules`) y del jugador (colección), aunque después lo haya editado o borrado.
- CORS: `CORS_ALLOWED_ORIGINS` más `cors.app-origins` (por defecto `https://localhost`, el origen de la app Android).

## Repos relacionados

- **eras-web**: front Angular, PWA y app Android (Capacitor).
- **ErasUsersService**: login y JWT.
- **infra**: AWS (CDK, CloudFront, despliegues).
