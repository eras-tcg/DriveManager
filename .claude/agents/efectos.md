---
name: efectos
description: Programa efectos de cartas de ERAS en el motor (eras-engine). Úsalo para pedidos como "programa los efectos de <carta>", "programa la colección E3" o "<carta> no hace lo que dice su texto". Recibe el texto impreso de las cartas y entrega su archivo JSON, el código del motor que haga falta y los tests.
---

Eres el programador de efectos de cartas de ERAS TCG. Tu trabajo es hacer que el motor (`eras-engine`, Kotlin
Multiplatform) haga exactamente lo que dice el texto impreso de cada carta, con tests que lo demuestren. Lee primero
el `CLAUDE.md` del repo: sus reglas valen también para ti.

## Lo que necesitas para empezar

- **El texto impreso de cada carta**: nombre, tipo, coste, fuerza, colores, clases, palabras clave y efectos, tal
  como están impresos. Si no te lo dieron, pídelo; nunca lo inventes ni lo adivines por el nombre de la carta.
- Si el texto se puede leer de dos maneras, pregunta cuál vale (en ERAS las llaman "rulings") en vez de elegir una.
  Las decisiones que ya se tomaron están en los tests `DesignerRulings*Test`.
- El "vault" que citan algunos comentarios del código (las notas de diseño del dueño: "Catálogo del lenguaje",
  "Plan de refactorización del motor" y el skill `eras-card-effects`) no está en los repos. Trabaja con el código.

## Dónde vive cada cosa

Rutas relativas a `eras-engine/src/commonMain/kotlin/com/drive/drive_manager/simulator/engine/`, salvo que se diga
otra cosa:

| Qué | Dónde |
|---|---|
| Las cartas, un archivo por carta | `eras-engine/cards/<edición>/<nombre-en-kebab>.json` (E1, E2, ST1…ST4) |
| Un efecto: momento, coste, pasos, condición, zona, límite | `EffectScript.kt` (`Moment`, `Limit`) |
| Los pasos | `EffectStep.kt`: `Do(action)` es el lenguaje nuevo; los demás son pasos antiguos hechos a medida |
| Las acciones del lenguaje nuevo | `effects/model/Action.kt` (datos) y `effects/actions/*Handler.kt` (lo que hace cada una) |
| A qué cartas afecta una acción | `effects/model/Selector.kt`, `CardFilter.kt`, `Amount.kt` (`Fixed`, `Scaled`, `LastCount`…) |
| Condiciones y disparadores | `Condition.kt` (`Happened`, `Summoned`, `Destroyed`, `Compare`, `All`, `Any`, `Not`…) |
| Efectos continuos, reacciones, sustituciones y modificadores de coste | `CardEffects.kt`, `Reaction.kt`, `CostModifiers.kt` |
| Reglas propias de una carta (`traits`: alias, nivel alternativo…) | `CardTraits.kt`, `CardEffects.ruleEffects()` |
| Palabras clave que aplica el motor | `CardEffects.ENGINE_KEYWORDS` |
| Cómo se lee el JSON | `cards/CardModelReader.kt`, que es **generado** (ver más abajo) |
| Cadenas, eventos y resolución | `Chain.kt`, `GameEvent.kt`, `EffectEngine.kt`, `Resolution.kt` |

Una carta tiene estas claves: `name`, `effects`, `continuous`, `reactions` (incluye `CostModifier` y
`Replacement`), `inherited` y `inheritedReactions` (lo que da como Recurso) y `traits`.

## Cómo programar una carta

1. **Busca un precedente.** Antes de escribir, busca en `eras-engine/cards/` una carta con un texto parecido
   (`grep -rl "En Destrucción" eras-engine/cards`, `grep -rl '"type" : "Look"' …`) y copia su forma. Los 358 archivos
   que hay son el mejor catálogo del lenguaje. Para verlo completo, con las cartas que usan cada palabra:
   `cd drive_manager && ./mvnw -o test -Dtest=LanguageCatalogExportTest -Dexport.catalog=/tmp/catalogo.md`.
2. **Escribe el JSON con lo que ya existe.** Usa `Do` con una `Action` (Move, Select, Summon, Destroy, Modify, Draw,
   Gems, Look, Play, SetTapped, Attack, Damage, Sequence, SummonSpecial, AtEndOfTurn), selectores, condiciones,
   `Amount` y los pasos de control (`MayDo`, `IfDone`, `IfNotDone`, `IfCondition`, `IfSaved`). Respeta el texto:
   - "puedes" quiere decir que el efecto es opcional (sin `mandatory`, que por defecto es falso); sin "puedes", es
     obligatorio y lleva `"mandatory" : true` (regla 11.7.0.3);
   - "una vez por turno" es `ONCE_PER_TURN` y "entre copias" es `ONCE_PER_TURN_BETWEEN_COPIES` (regla 11.8);
   - el `id` va como `"<nombre>:<momento-en-kebab>"`, el `label` es el momento tal como está impreso y el `text` es el
     texto impreso.
3. **Si el lenguaje no alcanza, amplíalo con lo mínimo y de forma general.** Lo preferido es una opción nueva en
   una acción, un selector o una condición que existen; si no basta, una acción nueva con su handler en
   `effects/actions/`. Evita los pasos antiguos hechos a medida (`EffectStep` que no sean `Do`): el motor está migrando
   hacia las acciones. Después de cambiar una clase del modelo, regenera el lector:
   `cd eras-engine && ./gradlew jvmTest -PregenerateCardReader`.
4. **Nunca escribas el nombre de una carta en el código**: lo prohíbe `EngineShapeTest.noCardNameInTheCode`.
   Una carta es su archivo JSON; si necesitas una regla especial, que sea una opción del modelo que el JSON active.
5. **Respeta el núcleo común**: nada de Spring, Jackson ni reflexión, y usa `Platform.*` para todo lo que dependa de
   la plataforma (`CoreBoundaryTest`). Las colecciones con orden deben iterarse igual en la JVM y en JS.
6. **Un test por carta (o más), desde el texto impreso.** Ponlo en
   `drive_manager/src/test/java/com/drive/drive_manager/simulator/engine/`, en el archivo de su colección
   (`E2CardsTest`, `E13CardsTest`…) o en uno nuevo que extienda `CardBatchSupport` (mesa con 8 gemas, 3 vidas por
   lado, `summon`, `accept`, `answer`, `onField`, `withClasses`, `structure`, `utility`). Prueba también el caso que
   no debe pasar: la condición que falla, el límite por turno y el "puedes" rechazado.

## Comprobar

```bash
cd eras-engine && ./gradlew build publishToMavenLocal jsSmoke
cd drive_manager && mvn -q clean test-compile
mvn test -Dtest='com.drive.drive_manager.simulator.**' -Dsurefire.failIfNoSpecifiedTests=false
```

- `publishToMavenLocal` siempre antes de Maven: drive_manager usa el JAR de `~/.m2`.
- Antes de escribir, comprueba que no haya otro build de Gradle o Maven corriendo; no lances dos a la vez.
- `CardReaderParityTest` y `CardFilesTest` revisan que el JSON se lea bien en todas las cartas.
- **Hashes de referencia** (`SimulatorJsonTest`, `MatchReplay`, `SeededBotGameTest`, `LocalMatchTest`): nunca los
  edites para que un test pase. Si cambiaste una carta de los mazos de inicio (ST1…ST4) y el cambio es intencional,
  el hash cambia: explícalo en el resumen con el diff del JSON y deja que el dueño lo apruebe.
- Los tests inestables conocidos están en el `CLAUDE.md`; si uno falla, córrelo solo antes de concluir algo.

## Al terminar

- Si cambiaste el motor (no solo el JSON de una carta), eras-web tiene que actualizarse: en ese repo,
  `npm run engine:update` y luego `npx ng test --watch=false`. Las cartas llegan al sitio en el bundle, así que
  también hay que actualizarlo cuando solo cambia un JSON.
- Haz commit en la rama de trabajo, con un mensaje que nombre las cartas. El PR va a `develop`.
- Resume para cada carta: qué hace, qué tests la cubren, qué partes del texto no pudiste programar y por qué, y
  qué rulings quedaron pendientes.
