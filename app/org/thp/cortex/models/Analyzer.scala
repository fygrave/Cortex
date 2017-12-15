package org.thp.cortex.models

import java.nio.file.{ Path, Paths }
import javax.inject.{ Inject, Singleton }

import scala.concurrent.Future
import scala.util.Try

import play.api.libs.json._

import org.scalactic._
import org.scalactic.Accumulation._
import org.thp.cortex.models.JsonFormat.{ analyzerConfigItemOptionFormat, analyzerConfigItemTypeFormat, analyzerStatusFormat }

import org.elastic4play.{ AttributeError, InvalidFormatAttributeError, MissingAttributeError }
import org.elastic4play.controllers.JsonInputValue
import org.elastic4play.models.{ Attribute, AttributeDef, BaseEntity, EntityDef, HiveEnumeration, ModelDef, MultiAttributeFormat, OptionalAttributeFormat, AttributeFormat ⇒ F, AttributeOption ⇒ O }

object AnalyzerStatus extends Enumeration with HiveEnumeration {
  type Type = Value
  val Enabled, Disabled = Value
}

object AnalyzerConfigItemType extends Enumeration with HiveEnumeration {
  type Type = Value
  val string, number, boolean = Value
}

object AnalyzerConfigItemOption extends Enumeration with HiveEnumeration {
  type Type = Value
  val multi, required = Value
}

case class ConfigurationDefinitionItem(
    name: String,
    description: String,
    tpe: AnalyzerConfigItemType.Type,
    options: Seq[AnalyzerConfigItemOption.Type],
    defaultValue: Option[JsValue]) {
  def isRequired = options.contains(AnalyzerConfigItemOption.required)
  def isMulti = options.contains(AnalyzerConfigItemOption.multi)

  private def check(v: JsValue): JsValue Or Every[AttributeError] = {
    import AnalyzerConfigItemType._
    v match {
      case _: JsString if tpe == string   ⇒ Good(v)
      case _: JsNumber if tpe == number   ⇒ Good(v)
      case _: JsBoolean if tpe == boolean ⇒ Good(v)
      case _                              ⇒ Bad(One(InvalidFormatAttributeError(s"$name[]", tpe.toString, JsonInputValue(v))))
    }
  }
  def read(config: JsObject): (String, JsValue) Or Every[AttributeError] = {
    (config \ name).toOption
      .orElse(defaultValue)
      .map {
        case JsArray(values) if isMulti ⇒ values.validatedBy(check).map(a ⇒ name -> JsArray(a))
        case value if !isMulti          ⇒ check(value).map(name -> _)
        case value                      ⇒ Bad(One(InvalidFormatAttributeError(name, tpe.toString, JsonInputValue(value))))
      }
      .getOrElse {
        if (isMulti) Good(name -> JsArray.empty)
        else if (isRequired) Bad(One(MissingAttributeError(name)))
        else Good(name -> JsNull)
      }
  }
}

trait AnalyzerAttributes {
  _: AttributeDef ⇒
  private val configurationDefinitionAttributes = Seq(
    Attribute("analyzer", "name", F.stringFmt, Nil, None, "Configuration item name"),
    Attribute("analyzer", "description", F.stringFmt, Nil, None, "Description of the configuration item"),
    Attribute("analyzer", "type", F.enumFmt(AnalyzerConfigItemType), Nil, None, "Type of the configuration item"),
    Attribute("analyzer", "options", MultiAttributeFormat(F.enumFmt(AnalyzerConfigItemOption)), Nil, None, "Options of the configuration item"),
    Attribute("analyzer", "defaultValue", OptionalAttributeFormat(F.stringFmt), Nil, None, "DefaultValue of the configuration item"))

  val analyzerId = attribute("_id", F.stringFmt, "Id of the analyer", O.model)
  val name = attribute("name", F.stringFmt, "Name of the analyzer")
  val status = attribute("status", F.enumFmt(AnalyzerStatus), "Status of the analyzer")
  val version = attribute("version", F.stringFmt, "Version of the analyzer")
  val description = attribute("description", F.stringFmt, "Description of the analyzer")
  val dataTypeList = multiAttribute("dataTypeList", F.stringFmt, "Type of observables that can be processed by this analyzer")
  val author = attribute("author", F.stringFmt, "Author of the analyzer")
  val url = attribute("url", F.stringFmt, "Url of the analyzer's home page")
  val license = attribute("license", F.stringFmt, "License of the analyzer")
  val directory = attribute("directory", F.stringFmt, "Directory where analyzer is installed")
  val command = attribute("command", F.stringFmt, "Analyzer command line", O.sensitive)
  val configurationItems = multiAttribute("configurationItems", F.objectFmt(configurationDefinitionAttributes), "Configuration items")
}

@Singleton
class AnalyzerModel @Inject() () extends ModelDef[AnalyzerModel, Analyzer]("analyzer", "Analyzer", "/analyzer") with AnalyzerAttributes {

  override def creationHook(parent: Option[BaseEntity], attrs: JsObject): Future[JsObject] = {
    Future.fromTry {
      for {
        name ← Try((attrs \ "name").as[String])
        version ← Try((attrs \ "version").as[String])
        id = Analyzer.computeId(name, version)
      } yield attrs + ("_id" -> JsString(id))
    }
  }
}

object Analyzer {
  def computeId(name: String, version: String): String = (name + "_" + version).replaceAll("\\.", "_")
}

class Analyzer(model: AnalyzerModel, attributes: JsObject) extends EntityDef[AnalyzerModel, Analyzer](model, attributes) with AnalyzerAttributes {
  lazy val configurationDefinition: Seq[ConfigurationDefinitionItem] = {
    configurationItems().flatMap { ci ⇒
      for {
        name ← (ci \ "name").asOpt[String]
        description ← (ci \ "description").asOpt[String]
        tpe ← (ci \ "type").asOpt[AnalyzerConfigItemType.Type]
        options = (ci \ "options").asOpt[Seq[AnalyzerConfigItemOption.Type]].getOrElse(Nil)
        defaultValue = (ci \ "defaultValue").asOpt[JsValue]
      } yield ConfigurationDefinitionItem(name, description, tpe, options, defaultValue)
    }
  }
  def cmd: Path = Paths.get(directory()).resolve(Paths.get(command()))
}

//
//  private[ExternalAnalyzerSrv] lazy val analyzeExecutionContext: ExecutionContext =
//    akkaSystem.dispatchers.lookup("analyzer")
//  private[ExternalAnalyzerSrv] lazy val globalConfig: JsObject =
//    (analyzerConfig \ "global").asOpt[JsObject].getOrElse(JsObject(Nil))
//  private[ExternalAnalyzerSrv] lazy val logger =
//    Logger(getClass)
//
//  lazy val list: Seq[ExternalAnalyzer] = {
//    for {
//      analyzerDir ← Try(Files.newDirectoryStream(analyzerPath).toSeq).getOrElse {
//        logger.warn(s"Analyzer directory ($analyzerPath) is not found")
//        Nil
//      }
//      if Files.isDirectory(analyzerDir)
//      infoFile ← Files.newDirectoryStream(analyzerDir, "*.json").toSeq
//      if Files.isReadable(infoFile)
//      analyzer ← Try(readInfo(infoFile).as[ExternalAnalyzer](reads))
//        .recoverWith {
//          case error ⇒
//            logger.warn(s"Load of analyzer $infoFile fails", error)
//            Failure(error)
//        }
//        .toOption
//        .flatMap {
//          case a if disabledAnalyzers.contains(a.id) ⇒
//            logger.info(s"Analyzer ${a.name} ${a.version} (${a.id}) is disabled")
//            None
//          case a ⇒
//            logger.info(s"Register analyzer ${a.name} ${a.version} (${a.id})")
//            Some(a)
//        }
//    } yield analyzer
//  }
//
//  def get(analyzerId: String): Option[ExternalAnalyzer] = list.find(_.id == analyzerId)
//
//  private val osexec =
//    if (System.getProperty("os.name").toLowerCase.contains("win"))
//      (c: String) ⇒ s"""cmd /c $c"""
//    else
//      (c: String) ⇒ s"""sh -c "./$c" """
//
//  def analyze(analyzer: ExternalAnalyzer, artifact: Artifact): Future[Report] = {
//    Future {
//      val input = artifact match {
//        case FileArtifact(file, attributes) ⇒ attributes + ("file" → JsString(file.getAbsoluteFile.toString)) + ("config" → analyzer.config)
//        case DataArtifact(data, attributes) ⇒ attributes + ("data" → JsString(data)) + ("config" → analyzer.config)
//      }
//      val output = new StringBuffer
//      val error = new StringBuffer
//      try {
//        logger.info(s"Execute ${osexec(analyzer.command.getFileName.toString)} in ${analyzer.command.getParent.toFile.getAbsoluteFile.getName}")
//        Process(osexec(analyzer.command.getFileName.toString), analyzer.command.getParent.toFile).run(
//          new ProcessIO(
//            { stdin ⇒
//              try stdin.write(input.toString.getBytes("UTF-8"))
//              finally stdin.close()
//            }, { stdout ⇒
//              val reader = new BufferedReader(new InputStreamReader(stdout, "UTF-8"))
//              try BasicIO.processLinesFully { line ⇒
//                output.append(line).append(System.lineSeparator())
//                ()
//              }(reader.readLine _)
//              finally reader.close()
//            }, { stderr ⇒
//              val reader = new BufferedReader(new InputStreamReader(stderr, "UTF-8"))
//              try BasicIO.processLinesFully { line ⇒
//                error.append(line).append(System.lineSeparator())
//                ()
//              }(reader.readLine _)
//              finally reader.close()
//            })).exitValue
//        Json.parse(output.toString).as[Report]
//      }
//      catch {
//        case _: JsonMappingException ⇒
//          error.append(output)
//          FailureReport(s"Error: Invalid output\n$error", JsNull)
//        case _: JsonParseException ⇒
//          error.append(output)
//          FailureReport(s"Error: Invalid output\n$error", JsNull)
//        case t: Throwable ⇒
//          FailureReport(t.getMessage + ":" + t.getStackTrace.mkString("", "\n\t", "\n"), JsNull)
//      }
//    }(analyzeExecutionContext)
//  }
//
//  private[ExternalAnalyzerSrv] def readInfo(file: Path): JsValue = {
//    val source = scala.io.Source.fromFile(file.toFile)
//    try Json.parse(source.mkString)
//    finally source.close()
//  }
//
//  private[ExternalAnalyzerSrv] val reads: Reads[ExternalAnalyzer] =
//    for {
//      name ← (__ \ "name").read[String]
//      version ← (__ \ "version").read[String]
//      description ← (__ \ "description").read[String]
//      dataTypeList ← (__ \ "dataTypeList").read[Seq[String]]
//      author ← (__ \ "author").read[String]
//      url ← (__ \ "url").read[String]
//      license ← (__ \ "license").read[String]
//      command ← (__ \ "command").read[String]
//      absoluteCommand = analyzerPath.resolve(Paths.get(command.replaceAll("[\\/]", File.separator)))
//      config ← (__ \ "config").read[JsObject]
//      baseConfigKey ← (__ \ "baseConfig").readNullable[String]
//      baseConfig = baseConfigKey
//        .flatMap(bc ⇒ (analyzerConfig \ bc).asOpt[JsObject])
//        .getOrElse(Json.obj())
//    } yield ExternalAnalyzer(
//      name,
//      version,
//      description,
//      dataTypeList,
//      author,
//      url,
//      license,
//      absoluteCommand, globalConfig deepMerge baseConfig deepMerge config)
//}