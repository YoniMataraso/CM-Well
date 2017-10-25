/**
  * Copyright 2015 Thomson Reuters
  *
  * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */


package controllers

import cmwell.domain._
import cmwell.formats._
import cmwell.fts._
import cmwell.ws.Streams
import cmwell.ws.adt.{BulkConsumeState, ConsumeState}
import cmwell.ws.util._
import logic.CRUDServiceFS
import play.api.mvc._
import wsutil._
import javax.inject._

import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent._
import scala.util.{Failure, Success}
import com.typesafe.scalalogging.LazyLogging
import cmwell.syntaxutils._
import cmwell.web.ld.cmw.CMWellRDFHelper
import cmwell.ws.Streams.Flows
import play.api.http.Writeable

import scala.math.{max, min}

@Singleton
class BulkScrollHandler @Inject()(crudServiceFS: CRUDServiceFS,
                                  tbg: NbgToggler,
                                  streams: Streams,
                                  cmwellRDFHelper: CMWellRDFHelper,
                                  formatterManager: FormatterManager,
                                  action: DefaultActionBuilder,
                                  components: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(components) with LazyLogging with TypeHelpers {

  def cache(nbg: Boolean) = if(nbg || tbg.get) crudServiceFS.nbgPassiveFieldTypesCache else crudServiceFS.obgPassiveFieldTypesCache

  //consts
  val paginationParamsForSingleResult = PaginationParams(0, 1)
  val paginationParamsForSingleResultWithOffset = PaginationParams(1000, 1) //TODO: take default offset from configuration

  def infotonWriteable(formatter: Formatter) = new Writeable[Infoton](formattableToByteString(formatter),Some(formatter.mimetype))

  sealed trait RangeForConsumption
  case class CurrRangeForConsumption(from: Long, to: Long, nextTo: Option[Long]) extends RangeForConsumption
  case class NextRangeForConsumption(from: Long, to: Option[Long]) extends RangeForConsumption
  case class ThinSearchParams(pathFilter: Option[PathFilter], fieldFilters: Option[FieldFilter], withHistory: Boolean, withDeleted: Boolean)

  def fieldsFiltersFromTimeframeAndOptionalFilters(from: Long, to: Long, ffsOpt: Option[FieldFilter]): FieldFilter = ffsOpt.fold(onlyTimeframeFieldFilters(from, to)) {
    case ff @ SingleFieldFilter(Should, _, _, _) => MultiFieldFilter(Must, MultiFieldFilter(Must, Seq(ff)) :: getFieldFilterSeq(from, to))
    case ff: SingleFieldFilter => MultiFieldFilter(Must, ff :: getFieldFilterSeq(from, to))
    case ff @ MultiFieldFilter(Should, _) => MultiFieldFilter(Must, MultiFieldFilter(Must, Seq(ff)) :: getFieldFilterSeq(from, to))
    case ff: MultiFieldFilter => MultiFieldFilter(Must, ff :: getFieldFilterSeq(from, to))
  }

  def fieldsFiltersforSortedSearchFromTimeAndOptionalFilters(from: Long, ffsOpt: Option[FieldFilter]): FieldFilter = {
    val fromFilter: FieldFilter = SingleFieldFilter(Must, GreaterThanOrEquals, "system.indexTime", Some(from.toString))
    ffsOpt.fold(fromFilter) {
      case ff@SingleFieldFilter(Should, _, _, _) => MultiFieldFilter(Must, Seq[FieldFilter](MultiFieldFilter(Must, Seq(ff)), fromFilter))
      case ff: SingleFieldFilter => MultiFieldFilter(Must, Seq(ff,fromFilter))
      case ff@MultiFieldFilter(Should, _) => MultiFieldFilter(Must, Seq(MultiFieldFilter(Must, Seq(ff)), fromFilter))
      case ff: MultiFieldFilter => MultiFieldFilter(Must, Seq(ff, fromFilter))
    }
  }

  def onlyTimeframeFieldFilters(from: Long, to: Long) = {
    MultiFieldFilter(Must, getFieldFilterSeq(from, to))
  }

  def getFieldFilterSeq(from: Long, to: Long) = {
    List(
      SingleFieldFilter(Must, GreaterThanOrEquals, "system.indexTime", Some(from.toString)),
      SingleFieldFilter(Must, LessThan, "system.indexTime", Some(to.toString))
    )
  }

  type ErrorMessage = String

  def findValidRange(thinSearchParams: ThinSearchParams,
                     from: Long,
                     threshold: Long,
                     timeoutMarker: Future[Unit])(implicit ec: ExecutionContext): Future[CurrRangeForConsumption] = {

    logger.debug(s"findValidRange: from[$from], threshold[$threshold], tsp[$thinSearchParams]")

    val ThinSearchParams(pf, ffsOpt, h, d) = thinSearchParams
    val now = org.joda.time.DateTime.now().minusSeconds(30).getMillis

    lazy val toSeed: Future[Long] = {
      val ffs = fieldsFiltersforSortedSearchFromTimeAndOptionalFilters(from,ffsOpt)
      crudServiceFS.thinSearch(
          pathFilter = pf,
          fieldFilters = Option(ffs),
          paginationParams = paginationParamsForSingleResultWithOffset,
          withHistory = h,
          fieldSortParams = SortParam("system.indexTime" -> Asc),
          withDeleted = d
        ).map {
        case SearchThinResults(_, _, _, results, _) =>
          //In case that there are more than the initial seed infotons (=1000) with the same index time the "from" will be equal to the "to"
          //This will fail the FieldFilter requirements (see getFieldFilterSeq) and thus approx. 1 second is added to the "from" as the new "to"
          results.headOption.fold(now)(i => math.max(i.indexTime,from+1729L)) //https://en.wikipedia.org/wiki/1729_(number)
      }
    }

    def expandTimeRange(to: Long, nextToForOptimization: Option[Long] = None)
                       (searchFunction: Long => Future[SearchThinResults]): Future[Either[(Long, Long, Option[Long]) , CurrRangeForConsumption]] = {
      //stop conditions: 1. in range. 2. out of range 3. next to > now 4. early cut off (return the last known position to be "not enough" - the previous to)
      logger.debug(s"expandTimeRange: from[$from], to[$to], nextToForOptimization[$nextToForOptimization]")
      if (timeoutMarker.isCompleted) Future.successful(Right(CurrRangeForConsumption(from, to - (to - from)/2, None)))
      //if to>=now then 1. the binary search should be between the previous position and now or 2. the now position itself. Both cases will be check in the below function
      else if (to >= now) checkRangeUpToNow(to - (to - from)/2)(searchFunction)
      else {
        searchFunction(to).flatMap {
          //not enough results - keep expanding
          case SearchThinResults(total, _, _, _, _) if total < threshold * 0.5 => expandTimeRange(to + to - from)(searchFunction)
          //in range - return final result
          case SearchThinResults(total, _, _, _, _) if total < threshold * 1.5 => Future.successful(Right(CurrRangeForConsumption(from, to, None)))
          //too many resutls - return the position to start the binary search from
          case SearchThinResults(total, _, _, _, _) => {
            val nextToOptimizedForTheNextToken = if (total < threshold * 3) Some(to) else None
            //The last step got us to this position
            val lastStep = (to - from) / 2
            val toToStartSearchFrom = to - lastStep / 2
            Future.successful(Left(toToStartSearchFrom, lastStep / 4, nextToOptimizedForTheNextToken))
          }
        }
      }
    }

    def checkRangeUpToNow(rangeStart: Long)(searchFunction: Long => Future[SearchThinResults]): Future[Either[(Long, Long, Option[Long]) , CurrRangeForConsumption]] = {
      logger.debug(s"checkRangeUpToNow: from[$from], rangeStart[$rangeStart]")
      //This function will be called ONLY when the previous step didn't have enough results
      searchFunction(now).map { sr =>
        if (sr.total <= threshold * 1.5) Right(CurrRangeForConsumption(from, now , None))
        else {
          val nextToOptimizedForTheNextToken = if (sr.total < threshold * 3) Some(now) else None
          //rangeStart is the last known position to be with not enough results. This is the lower bound for the binary search
          //range is the range of the binary search. The whole search will be between rangeStart and now
          val range = now - rangeStart
          //The next position to be checked using the binary search
          val middle = rangeStart + range / 2
          //In case the next iteration won't finish, this is the step to be taken. The step is half of the step that was taken to get to the middle point
          val step = range / 4
          Left(middle, step, nextToOptimizedForTheNextToken)
        }
      }
    }

    def shrinkingStepBinarySearch(timePosition: Long, step: Long, nextTo: Option[Long])(searchFunction: Long => Future[SearchThinResults]): Future[CurrRangeForConsumption] = {
      logger.debug(s"shrinkingStepBinarySearch: from[$from], timePosition[$timePosition], step[$step], nextTo[$nextTo]")
      //stop conditions: 1. in range. 2. early cut off
      //In case of an early cut off we have 2 options:
      //1. the previous didn't have enough results - we can use it
      //2. the previous had too many results - we cannot use it but we can use the position before it which is our position minus twice the given step
      if (timeoutMarker.isCompleted) Future.successful(CurrRangeForConsumption(from, timePosition - (step * 2), nextTo))
      else {
        searchFunction(timePosition).flatMap {
          //not enough results - keep the search up in the time line
          case SearchThinResults(total, _, _, _, _) if total < threshold * 0.5 => shrinkingStepBinarySearch(timePosition + step, step / 2, nextTo)(searchFunction)
          //in range - return final result
          case SearchThinResults(total, _, _, _, _) if total < threshold * 1.5 => Future.successful(CurrRangeForConsumption(from, timePosition, nextTo))
          //too many resutls - keep the search down in the time line
          case SearchThinResults(total, _, _, _, _) => {
            val nextToOptimizedForTheNextToken = nextTo orElse (if (total < threshold * 3) Some(timePosition) else None)
            shrinkingStepBinarySearch(timePosition - step, step / 2, nextToOptimizedForTheNextToken)(searchFunction)
          }
        }
      }
    }

    toSeed.flatMap { to =>
      logger.debug(s"findValidRange: toSeed[$to], from[$from], tsp[$thinSearchParams]")
      val searchFunction = (to: Long) => {
        val ffs = fieldsFiltersFromTimeframeAndOptionalFilters(from, to, ffsOpt)
        crudServiceFS.thinSearch(
          pathFilter = pf,
          fieldFilters = Option(ffs),
          paginationParams = paginationParamsForSingleResult,
          withHistory = h,
          withDeleted = d
        )
      }
      expandTimeRange(to,None)(searchFunction).flatMap {
        case Left((timePosition, step, nextToOptimization)) => shrinkingStepBinarySearch(timePosition, step, nextToOptimization)(searchFunction)
        case Right(result) => Future.successful(result)
      }
    }
  }

  def createPathFilter(path: Option[String], recursive: Boolean) = path.flatMap{ p =>
    if (p == "/" && recursive) None
    else Some(PathFilter(p, recursive))
  }

  def retrieveNextState(ff: Option[FieldFilter],
                        from: Long,
                        recursive: Boolean,
                        withHistory: Boolean,
                        withDeleted: Boolean,
                        path: Option[String],
                        chunkSizeHint: Long)(implicit ec: ExecutionContext): Future[(BulkConsumeState,Option[Long])] = {

    val futureMarksTheTimeOut = cmwell.util.concurrent.SimpleScheduler.schedule(cmwell.ws.Settings.consumeBulkBinarySearchTimeout)(())
    val pf = createPathFilter(path, recursive)
    if(from == 0) {
      crudServiceFS.thinSearch(
          pathFilter = pf,
          fieldFilters = ff,
          paginationParams = paginationParamsForSingleResult,
          withHistory = withHistory,
          fieldSortParams = SortParam("system.indexTime" -> Asc),
          withDeleted = withDeleted
        ).flatMap {
        case SearchThinResults(_, _, _, results, _) => {
          lazy val consumeEverythingWithoutNarrowingSearch = {
            val now = org.joda.time.DateTime.now().minusSeconds(30).getMillis
            Future.successful(BulkConsumeState(0L, Some(now), path, withHistory, withDeleted, recursive, chunkSizeHint, ff) -> Option.empty[Long])
          }
          // if no results were found - just go ahead. scroll everything, which will return nothing -> 204
          results.headOption.fold(consumeEverythingWithoutNarrowingSearch) { i =>
            // first infoton found, gets to be the new from instead of 0, and we are going to find a real valid range
            val thinSearchParams = ThinSearchParams(pf, ff, withHistory, withDeleted)
            findValidRange(thinSearchParams, i.indexTime, threshold = chunkSizeHint,timeoutMarker = futureMarksTheTimeOut).map {
              case CurrRangeForConsumption(f, t, tOpt) =>
                BulkConsumeState(f, Some(t), path, withHistory, withDeleted, recursive, chunkSizeHint, ff) -> tOpt
            }
          }
        }
      }.recoverWith {
        case e: Throwable => {
          logger.error(s"failed to retrieveNextState($ff,$from,$recursive,$withHistory,$withDeleted,$path,$chunkSizeHint)", e)
          Future.failed(e)
        }
      }
    } else {
      val thinSearchParams = ThinSearchParams(pf, ff, withHistory, withDeleted)
      findValidRange(thinSearchParams, from, threshold = chunkSizeHint,timeoutMarker = futureMarksTheTimeOut).map { case CurrRangeForConsumption(f, t, tOpt) =>
        BulkConsumeState(f, Some(t), path, withHistory, withDeleted, recursive, chunkSizeHint, ff) -> tOpt
      }.recoverWith {
        case e: Throwable =>{
          logger.error(s"failed to retrieveNextState($ff,$from,$recursive,$withHistory,$withDeleted,$path,$chunkSizeHint)", e)
          Future.failed(e)
        }
      }
    }
  }

  def getFormatter(request: Request[AnyContent], withHistory: Boolean, nbg: Boolean) = {

    (extractInferredFormatWithData(request) match {
      case (fmt,b) if Set("text", "path", "tsv", "tab", "nt", "ntriples", "nq", "nquads")(fmt.toLowerCase) || fmt.toLowerCase.startsWith("json") => Success(fmt -> b)
      case (badFormat,_) => Failure(new IllegalArgumentException(s"requested format ($badFormat) is invalid for as streamable response."))
    }).map { case (format,forceData) =>

      val withData: Option[String] = request.getQueryString("with-data") orElse{
        if(forceData) Some("json")
        else None
      }

      val withMeta: Boolean = request.queryString.keySet("with-meta")
      format match {
        case FormatExtractor(formatType) => {
        /* RDF types allowed in mstream are: ntriples, nquads, jsonld & jsonldq
         * since, the jsons are not realy RDF, just flattened json of infoton per line,
         * there is no need to tnforce subject uniquness. but ntriples, and nquads
         * which split infoton into statements (subject-predicate-object triples) per line,
         * we don't want different versions to "mix" and we enforce uniquness only in this case
         */
          val forceUniqueness: Boolean = withHistory && (formatType match {
            case RdfType(NquadsFlavor) => true
            case RdfType(NTriplesFlavor) => true
            case _ => false
          })
          //cleanSystemBlanks set to true, so we won't output all the meta information we usually output. it get's messy with streaming. we don't want each chunk to show the "document context"
          formatterManager.getFormatter(
            format = formatType,
            host = request.host,
            uri = request.uri,
            pretty = false,
            callback = request.queryString.get("callback").flatMap(_.headOption),
            fieldFilters = None,
            offset = None,
            length = None, //Some(500L),
            withData = withData,
            withoutMeta = !withMeta,
            filterOutBlanks = true,
            forceUniqueness = forceUniqueness,
            nbg = nbg
          ) -> withData.isDefined
        }
      }
    }
  }

  def handle(request: Request[AnyContent]): Future[Result] = {

    def wasSupplied(queryParamKey: String) = request.queryString.keySet(queryParamKey)

    val currStateEither = request.getQueryString("position").fold[Either[ErrorMessage, Future[(BulkConsumeState,Option[Long])]]] {
      Left("position param is mandatory")
    } { pos: String =>
      if (wasSupplied("qp"))
        Left("you can't specify `qp` together with `position` (`qp` is meant to be used only in the first iteration request. after that, continue iterating using the received `position`)")
      else if (wasSupplied("index-time"))
        Left("`index-time` is determined in the beginning of the iteration. can't be specified together with `position`")
      else if (wasSupplied("with-descendants") || wasSupplied("recursive"))
        Left("`with-descendants`/`recursive` is determined in the beginning of the iteration. can't be specified together with `position`")
      else if (wasSupplied("with-history"))
        Left("`with-history` is determined in the beginning of the iteration. can't be specified together with `position`")
      else if (wasSupplied("with-deleted"))
        Left("`with-deleted` is determined in the beginning of the iteration. can't be specified together with `position`")
      else if (wasSupplied("length-hint"))
        Left("`length-hint` is determined in the beginning of the bulk consume iteration. can't be specified together with `position`")
      else {
        ConsumeState.decode[BulkConsumeState](pos).map(bcs => bcs.copy(to = bcs.to.orElse(request.getQueryString("to-hint").flatMap(asLong)))) match {
          case Success(state @ BulkConsumeState(f, None, path, h, d, r, lengthHint, qpOpt)) =>
            Right(retrieveNextState(qpOpt, f, r, h, d, path, lengthHint))
          case Success(state @ BulkConsumeState(_, Some(t), _, _, _, _, _, _)) =>
            Right(Future.successful(state -> None))
          case Failure(err) =>
            Left(err.getMessage)
        }
      }
    }

    val nbg = request.getQueryString("nbg").flatMap(asBoolean).getOrElse(tbg.get)

    currStateEither match {
      case Left(err) => Future.successful(BadRequest(err))
      case Right(stateFuture) => stateFuture.flatMap {
        case (state@BulkConsumeState(from, Some(to), path, h, d, r, threshold, ffOpt), nextTo) => {
          if(request.queryString.keySet("debug-info")) {
            logger.info(s"""The search params:
                           |path             = $path,
                           |from             = $from,
                           |to               = $to,
                           |nextTo           = $nextTo,
                           |fieldFilters     = $ffOpt,
                           |withHistory      = $h,
                           |withDeleted      = $d,
                           |withRecursive    = $r """.stripMargin)
          }

          getFormatter(request, h, nbg) match {
            case Failure(exception) => Future.successful(BadRequest(exception.getMessage))
            case Success((formatter, withData)) => {

              // Gets a scroll source according to received HTTP request parameters
              def getScrollSource() = {
                (if (wasSupplied("slow-bulk")) {
                  streams.scrollSource(nbg,
                    pathFilter = createPathFilter(path, r),
                    fieldFilters = Option(fieldsFiltersFromTimeframeAndOptionalFilters(from, to, ffOpt)),
                    withHistory = h,
                    withDeleted = d)
                } else {
                  streams.superScrollSource(nbg,
                    pathFilter = createPathFilter(path, r),
                    fieldFilter = Option(fieldsFiltersFromTimeframeAndOptionalFilters(from, to, ffOpt)),
                    withHistory = h,
                    withDeleted = d)
                }).map { case (src, hits) =>
                  val s: Source[Infoton, NotUsed] = {
                    if (withData) src.via(Flows.iterationResultsToFatInfotons(nbg, crudServiceFS))
                    else src.via(Flows.iterationResultsToInfotons)
                  }
                  hits -> s
                }
              }

              getScrollSource().map {
                case (0L, _) => NoContent.withHeaders("X-CM-WELL-N" -> "0", "X-CM-WELL-POSITION" -> request.getQueryString("position").get)
                case (hits, source) => {
                  val positionEncoded = ConsumeState.encode(state.copy(from = to, to = nextTo))

                  Ok.chunked(source)(infotonWriteable(formatter))
                    .withHeaders(
                      "X-CM-WELL-N" -> hits.toString,
                      "X-CM-WELL-POSITION" -> positionEncoded,
                      "X-CM-WELL-TO" -> to.toString
                    )
                }
              }.recover(errorHandler)
            }
          }
        }
      }.recover(errorHandler)
    }
  }

  def parseQpFromRequest(qp: String, nbg: Boolean)(implicit ec: ExecutionContext): Future[Option[FieldFilter]] = {
    FieldFilterParser.parseQueryParams(qp) match {
      case Failure(err) => Future.failed(err)
      case Success(rff) => RawFieldFilter.eval(rff,cache(nbg),cmwellRDFHelper,nbg).map(Option.apply)
    }
  }

  private def addIndexTime(fromCassandra: Seq[Infoton], uuidToindexTime: Map[String, Long]): Seq[Infoton] = fromCassandra.map {
    case i: ObjectInfoton if i.indexTime.isEmpty => i.copy(indexTime = uuidToindexTime.get(i.uuid))
    case i: FileInfoton if i.indexTime.isEmpty => i.copy(indexTime = uuidToindexTime.get(i.uuid))
    case i: LinkInfoton if i.indexTime.isEmpty => i.copy(indexTime = uuidToindexTime.get(i.uuid))
    case i: DeletedInfoton if i.indexTime.isEmpty => i.copy(indexTime = uuidToindexTime.get(i.uuid))
    case i => i
  }

}
