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

import org.bson.{Document => _, _}

import quasar.api.table.ColumnType
import quasar.common.{CPath, CPathField}
import quasar.{IdStatus, ScalarStageSpec => Spec, ScalarStage, ScalarStages}

class MongoScalarStagesInterpreterSpec
    extends Spec.WrapSpec
    with Spec.ProjectSpec
    with Spec.PivotSpec
    with Spec.MaskSpec
    with Spec.CartesianSpec
    with StageInterpreterSpec {

  val idsPendingExamples: Set[Int] = Set()
  val wrapPendingExamples: Set[Int] = Set()
  val projectPendingExamples: Set[Int] = Set()
  val maskPendingExamples: Set[Int] = Set()
  val pivotPendingExamples: Set[Int] = Set(9, 10, 11)
  val cartesianPendingExamples: Set[Int] = Set()

  "Id statuses" >> {
    val input = ldjson("""
      {"_id": "0", "value": "foo"}
      {"_id": "1", "value": "bar"}
      {"_id": "2", "value": "baz"}""")
    "ExcludeId" >> {
      val actual = interpret(ScalarStages(IdStatus.ExcludeId, List()), input, (x => x))
      actual must bestSemanticEqual(input)
    }
    "IdOnly" >> {
      val expected = ldjson("""
        "0"
        "1"
        "2"""")
      val actual = interpret(ScalarStages(IdStatus.IdOnly, List()), input, (x => x))
      actual must bestSemanticEqual(expected)
    }
    "IncludeId" >> {
      val expected = ldjson("""
        ["0", {"_id": "0", "value": "foo"}]
        ["1", {"_id": "1", "value": "bar"}]
        ["2", {"_id": "2", "value": "baz"}]""")
      val actual = interpret(ScalarStages(IdStatus.IncludeId, List()), input, (x => x))
      actual must bestSemanticEqual(expected)
    }
  }

  "Cartesian special" >> {
    "cross fields when some are undefined" in {
      val input = ldjson("""
          { "a0": 1 }
          { "a0": 2, "b0": "foo" }
          { "b0": "bar" }
          """)
      val expected = ldjson("""
          { "a1": 1 }
          { "a1": 2, "b1": "foo" }
          { "b1": "bar" }
          """)
      val targets = Map(
        (CPathField("a1"), (CPathField("a0"), Nil)),
        (CPathField("b1"), (CPathField("b0"), Nil)))

      input must cartesianInto(targets)(expected)
    }

    "nested pivoting doesn't produce unnecessary empty fields" in {
      val input = ldjson("""
          { "a": 1, "b": [[ "two", "three" ], 12, ["four"]] }
          { "a": 2, "b": [{ "x": "four", "y": "five" }] }
          { "a": 3, "b": 42 }
          """)

      val expected = ldjson("""
          { "a": 1, "ba": "two" }
          { "a": 1, "ba": "three" }
          { "a": 1 }
          { "a": 1, "ba": "four" }
          { "a": 2 }
          { "a": 3 }
          """)

      val targets = Map(
        (CPathField("a"), (CPathField("a"), Nil)),

        (CPathField("ba"), (CPathField("b"), List(
          Mask(Map(CPath.Identity -> Set(ColumnType.Array))),
          Pivot(IdStatus.ExcludeId, ColumnType.Array),
          Mask(Map(CPath.Identity -> Set(ColumnType.Array))),
          Pivot(IdStatus.ExcludeId, ColumnType.Array)))))

      input must cartesianInto(targets)(expected)
    }
  }

  val RootKey: String = "rootKey"

  val RootProjection = Project(CPath.parse(".rootKey"))

  def rootWrapper(b: JsonElement): JsonElement = new BsonDocument(RootKey, b)

  def evalOneStage(stage: ScalarStage, stream: JsonStream): JsonStream =
    interpret(ScalarStages(IdStatus.ExcludeId, List(RootProjection, stage)), stream, rootWrapper)

  def evalWrap(wrap: Wrap, stream: JsonStream): JsonStream =
    evalOneStage(wrap, stream)

  def evalProject(project: Project, stream: JsonStream): JsonStream =
    evalOneStage(project, stream)

  def evalPivot(pivot: Pivot, stream: JsonStream): JsonStream =
    evalOneStage(pivot, stream)

  def evalMask(mask: Mask, stream: JsonStream): JsonStream =
    evalOneStage(mask, stream)

  def evalCartesian(cartesian: Cartesian, stream: JsonStream): JsonStream =
    evalOneStage(cartesian, stream)
}
