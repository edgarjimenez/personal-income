/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.personalincome.connectors

import org.joda.time.{DateTime, DateTimeZone}
import uk.gov.hmrc.personalincome.config.WSHttp
import uk.gov.hmrc.personalincome.domain._
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global


sealed trait Response {
  def status:Int
}
case class Success(status:Int) extends Response
case class Error(status:Int) extends Response

trait NtcConnector {
  def http: HttpGet with HttpPost

  def serviceUrl: String

  def authenticateRenewal(nino: TaxCreditsNino,
                          renewalReference: RenewalReference)(implicit headerCarrier: HeaderCarrier): Future[Option[TcrAuthenticationToken]] = {
    http.GET[Option[TcrAuthenticationToken]](s"$serviceUrl/tcs/${nino.value}/${renewalReference.value}/auth")
  }

  def claimantDetails(nino: TaxCreditsNino)(implicit headerCarrier: HeaderCarrier): Future[ClaimantDetails] = {
    http.GET[ClaimantDetails](s"$serviceUrl/tcs/${nino.value}/claimant-details")
  }

  def submitRenewal(nino: TaxCreditsNino,
                    renewalData: TcrRenewal)(implicit headerCarrier: HeaderCarrier): Future[Response] = {
    val uri = s"$serviceUrl/tcs/${nino.taxCreditsNino}/renewal"
    http.POST[TcrRenewal, HttpResponse](uri, renewalData, Seq()).map(response => {
      response.status match {
        case x if x >= 200 && x < 300 => Success(x)
        case _ => Error(response.status)
      }
    })
  }

}

object NtcConnector extends NtcConnector with ServicesConfig {
  override val http = WSHttp

  override lazy val serviceUrl = baseUrl("ntc")
}



trait LoadConfig {

  import com.typesafe.config.Config

  def config: Config
}

trait TaxCreditsSubmissionControlConfig extends LoadConfig {
  import net.ceedubs.ficus.readers.ValueReader
  import net.ceedubs.ficus.Ficus._

  private val submission = "microservice.services.ntc.submission"

  private implicit val nativeVersionReader: ValueReader[TaxCreditsSubmissionControl] = ValueReader.relative { nativeVersion =>
    TaxCreditsSubmissionControl(
      config.as[Boolean](s"$submission.shutter"),
      DateTime.parse(config.as[String](s"$submission.startDate")).toDateTime(DateTimeZone.UTC).withTimeAtStartOfDay(),
      DateTime.parse(config.as[String](s"$submission.endDate")).toDateTime(DateTimeZone.UTC)
    )
  }

  val submissionControl: TaxCreditsSubmissionControl = config.as[TaxCreditsSubmissionControl](submission)
}


sealed case class TaxCreditsSubmissionControl(shutter : Boolean, startDate : DateTime, endDate : DateTime)

object TaxCreditsSubmissionControl extends TaxCreditsSubmissionControlConfig{
  import com.typesafe.config.{Config, ConfigFactory}

  lazy val config: Config = ConfigFactory.load()
}