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

package uk.gov.hmrc.personalincome.domain

import play.api.libs.json.Json
import uk.gov.hmrc.domain.{Nino, SaUtr}

import scala.concurrent.{ExecutionContext, Future}

case class Accounts(nino: Option[Nino], saUtr: Option[SaUtr])

object Accounts {
  implicit val accountsFmt = {

    Json.format[Accounts]
  }
}

case class CustomerProfile(accounts: Accounts, personalDetails: PersonDetails)

object CustomerProfile {
  implicit val formats = {

    Json.format[CustomerProfile]
  }

  def create(accounts: () => Future[Accounts], personalDetails: (Option[Nino]) => Future[PersonDetails])(implicit ec : ExecutionContext) : Future[CustomerProfile] = {
    for {
      acc <- accounts()
      pd <- personalDetails(acc.nino)
    } yield CustomerProfile(acc, pd)
  }
}