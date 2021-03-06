/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.mongo

import slamdata.Predef._

import cats.effect.Sync
import cats.syntax.functor._
import cats.syntax.foldable._
import cats.instances.option._
import cats.instances.list._
import cats.syntax.order._

import org.bson.{BsonValue, BsonDocument}

import quasar.{ScalarStage, ScalarStages}
import quasar.IdStatus
import quasar.physical.mongo.interpreter._
import quasar.physical.mongo.expression._

import shims._

final case class Interpretation(
  stages: List[ScalarStage],
  docs: List[BsonDocument])

trait Interpreter {
  def interpret(stages: ScalarStages): Interpretation
  def refineInterpretation(key: String, interpretation: Interpretation): Interpretation
  def mapper(x: BsonValue): BsonValue
  def interpretStep(key: String, instruction: ScalarStage): Option[List[Pipe]]
}

class MongoInterpreter(version: Version, uniqueKey: String, pushdownLevel: PushdownLevel) extends Interpreter {
  def mapper(x: BsonValue): BsonValue =
    x.asDocument().get(uniqueKey)

  @scala.annotation.tailrec
  private def refineInterpretationImpl(key: String, interpretation: Interpretation): Interpretation =
    interpretation.stages match {
      case List() => interpretation
      case hd :: tail =>
        interpretStep(key, hd) flatMap (compilePipeline(version, _)) match {
        case None => interpretation
        case Some(docs) => refineInterpretationImpl(key, Interpretation(tail, interpretation.docs ++ docs))
      }
    }

  def refineInterpretation(key: String, interpretation: Interpretation): Interpretation =
    refineInterpretationImpl(key, interpretation)

  def interpretStep(key: String, instruction: ScalarStage): Option[List[Pipe]] = instruction match {
    case ScalarStage.Wrap(name) =>
      if (pushdownLevel < PushdownLevel.Light) None
      else Projection.safeField(name) map (Wrap(key, _))
    case ScalarStage.Mask(masks) =>
      if (pushdownLevel < PushdownLevel.Light) None
      else Mask(key, masks)
    case ScalarStage.Pivot(status, structure) =>
      if (pushdownLevel < PushdownLevel.Light) None
      else Pivot(key, status, structure)
    case ScalarStage.Project(path) =>
      if (pushdownLevel < PushdownLevel.Light) None
      else Projection.fromCPath(path) map (Project(key, _))
    case ScalarStage.Cartesian(cartouches) =>
      if (pushdownLevel < PushdownLevel.Full) None
      else Projection.safeCartouches(cartouches) flatMap (Cartesian(key, _, this))
  }

  private def initialProjection(idStatus: IdStatus): Pipe = idStatus match {
    case IdStatus.IdOnly => Pipeline.$project(Map(
      uniqueKey -> O.key("_id"),
      "_id" -> O.int(0)))
    case IdStatus.ExcludeId => Pipeline.$project(Map(
      uniqueKey -> O.steps(List()),
      "_id" -> O.int(0)))
    case IdStatus.IncludeId => Pipeline.$project(Map(
      uniqueKey -> O.array(List(O.key("_id"), O.steps(List()))),
      "_id" -> O.int(0)))
  }

  def interpret(stages: ScalarStages): Interpretation = {
    val initialDocs = compilePipe(version, initialProjection(stages.idStatus)) foldMap (List(_))
    refineInterpretation(uniqueKey, Interpretation(stages.stages, initialDocs))
  }
}

object MongoInterpreter {
  def apply[F[_]: Sync](version: Version, pushdownLevel: PushdownLevel): F[MongoInterpreter] =
    Sync[F].delay(java.util.UUID.randomUUID().toString) map (new MongoInterpreter(version, _, pushdownLevel))
}
