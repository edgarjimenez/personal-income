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

package uk.gov.hmrc.personalincome.controllers

import java.util.UUID

import com.ning.http.util.Base64
import org.joda.time.DateTime
import play.api.libs.json.{Json, JsValue}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.personalincome.config.MicroserviceAuditConnector
import uk.gov.hmrc.personalincome.connectors._
import uk.gov.hmrc.personalincome.controllers.action.{AccountAccessControlCheckOff, AccountAccessControlWithHeaderCheck, AccountAccessControl}
import uk.gov.hmrc.personalincome.domain._
import uk.gov.hmrc.personalincome.domain.userdata._
import uk.gov.hmrc.personalincome.services.{PersonalIncomeService, SandboxPersonalIncomeService, LivePersonalIncomeService}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.play.auth.microservice.connectors.ConfidenceLevel
import uk.gov.hmrc.play.http._
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global


class TestTaiConnector(taxSummaryDetails:Option[TaxSummaryDetails]) extends TaiTestConnector {
  override def http: HttpGet with HttpPost = ???

  override def serviceUrl: String = ???

  override def taxSummary(nino: Nino, year: Int)(implicit hc: HeaderCarrier, ex: ExecutionContext): Future[Option[TaxSummaryDetails]] = {
    Future.successful(taxSummaryDetails)
  }
}

class TestNtcConnector(response:Response, tcrAuthToken:Option[TcrAuthenticationToken], claimantDetails:ClaimantDetails) extends NtcTestConnector {
  override def http: HttpGet with HttpPost = ???

  override def serviceUrl: String = ???

  override def submitRenewal(nino: TaxCreditsNino,
                             renewalData: TcrRenewal)(implicit headerCarrier: HeaderCarrier, ex: ExecutionContext): Future[Response] = {
    Future.successful(response)
  }

  override def authenticateRenewal(nino: TaxCreditsNino,
                          renewalReference: RenewalReference)(implicit headerCarrier: HeaderCarrier, ex: ExecutionContext): Future[Option[TcrAuthenticationToken]] = {
    Future.successful(tcrAuthToken)
  }

  override def claimantDetails(nino: TaxCreditsNino)(implicit headerCarrier: HeaderCarrier, ex: ExecutionContext): Future[ClaimantDetails] = {
    Future.successful(claimantDetails)
  }
}

class TestAuthConnector(nino: Option[Nino]) extends AuthConnector {
  override val serviceUrl: String = "someUrl"

  override def serviceConfidenceLevel: ConfidenceLevel = ???

  override def http: HttpGet = ???

  override def accounts()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Accounts] = Future(Accounts(nino, None, false, false))

  override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] = Future(Unit)
}

class TestTaxCreditBrokerConnector(payment: PaymentSummary, personal: PersonalDetails, partner: PartnerDetails,
                                     children: Option[Children], exclusion:Option[Exclusion]) extends TaxCreditsBrokerTestConnector {
  private def serviceUnavailable = throw new ServiceUnavailableException("controlled error")

  override def getPaymentSummary(nino: TaxCreditsNino)(implicit headerCarrier: HeaderCarrier, ex: ExecutionContext) = Future(payment)
  override def getPersonalDetails(nino:TaxCreditsNino)(implicit headerCarrier: HeaderCarrier, ex: ExecutionContext) = Future(personal)
  override def getPartnerDetails(nino: TaxCreditsNino)(implicit headerCarrier: HeaderCarrier, ex: ExecutionContext) = Future(Some(partner))
  override def getChildren(nino: TaxCreditsNino)(implicit headerCarrier: HeaderCarrier, ex: ExecutionContext) = Future(children.getOrElse(serviceUnavailable))
  override def getExclusion(nino: TaxCreditsNino)(implicit headerCarrier: HeaderCarrier, ex: ExecutionContext) = Future(exclusion.getOrElse(serviceUnavailable))
}

class TestPersonalIncomeService(testTaiConnector:TestTaiConnector,
                                testAuthConnector:TestAuthConnector,
                                testNtcConnector:NtcConnector,
                                testTaxCreditBrokerConnector:TaxCreditsBrokerConnector,
                                testAuditConnector: uk.gov.hmrc.play.audit.http.connector.AuditConnector) extends LivePersonalIncomeService {
  var saveDetails:Map[String, String]=Map.empty



  override protected def audit(service: String, details: Map[String, String])(implicit hc: HeaderCarrier, ec : ExecutionContext) = {
    saveDetails=details
    Future.successful(AuditResult.Success)
  }

  override val taiConnector = testTaiConnector
  override val authConnector = testAuthConnector
  override val ntcConnector = testNtcConnector
  override val taxCreditBrokerConnector: TaxCreditsBrokerConnector = testTaxCreditBrokerConnector
  override val auditConnector = testAuditConnector
}

class TestAccessCheck(testAuthConnector: TestAuthConnector) extends AccountAccessControl {
  override val authConnector: AuthConnector = testAuthConnector
}

class TestAccountAccessControlWithAccept(testAccessCheck:AccountAccessControl) extends AccountAccessControlWithHeaderCheck {
  override val accessControl: AccountAccessControl = testAccessCheck
}


trait Setup {
  implicit val hc = HeaderCarrier()

  val journeyId = UUID.randomUUID().toString
  val nino = Nino("CS700100A")
  val taxSummaryDetails = TaxSummaryDetails(nino.value,1)

  val taxableIncome = TaxableIncome(taxFreeAmount=0,
                        incomeTax=0,
                        income=0,
                        taxCodeList=List.empty,
                        employmentPension = EmploymentPension(None),
                        investmentIncomeTotal = 0,
                        otherIncomeTotal =0,
                        benefitsTotal = 0,
                        taxableBenefitsTotal=0
                        )

  val lowCl = Json.parse("""{"code":"LOW_CONFIDENCE_LEVEL","message":"Confidence Level on account does not allow access"}""")
  val noNinoOnAccont = Json.parse("""{"code":"UNAUTHORIZED","message":"NINO does not exist on account"}""")

  val taxSummaryContainer = TaxSummaryContainer(TaxSummaryDetails(nino.value, 1), BaseViewModel(estimatedIncomeTax=0), None, Some(taxableIncome), None)
  val taxSummaryContainerGK = TaxSummaryContainer(TaxSummaryDetails(nino.value, 1, gateKeeper=Some(GateKeeper(gateKeepered=true, gateKeeperResults=List.empty))), BaseViewModel(estimatedIncomeTax=0), None, None, Some(GateKeeperDetails(TotalLiability(totalTax=0), DecreasesTax(total=0), increasesTax=IncreasesTax(total=0))))

  val incomeDetails = IncomeDetails(Some(10), Some(20), Some(30), Some(40), Some(true))
  val certainBenefits = CertainBenefits(false, false, false, false, false)
  val otherIncome = OtherIncome(Some(100), Some(false))
  val renewal = TcrRenewal(RenewalData(Some(incomeDetails), Some(incomeDetails), Some(certainBenefits)), None, Some(otherIncome), Some(otherIncome), false)
  val renewalReferenceUnknown = RenewalReference("some-reference")
  val renewalReference = RenewalReference("111111111111111")
  val renewalReferenceNines = RenewalReference("999999999999999")
  val weekly = "WEEKLY"
  val expectedNextDueDate = DateTime.parse("2015-07-16")
  val expectedPaymentCTC = Payment(140.12, expectedNextDueDate, Some(weekly))
  val expectedPaymentWTC = Payment(160.34, expectedNextDueDate, Some(weekly))
  val paymentSummary = PaymentSummary(Some(expectedPaymentWTC), Some(expectedPaymentCTC))

  val AGE16=DateTimeUtils.now.minusYears(16)
  val AGE15=DateTimeUtils.now.minusYears(15)
  val AGE13=DateTimeUtils.now.minusYears(13)
  val AGE17=DateTimeUtils.now.minusYears(17)

  val SarahSmith = Child("Sarah", "Smith",new DateTime(AGE16),false,false,true)
  val JosephSmith = Child("Joseph", "Smith",new DateTime(AGE15),false,false,true)
  val MarySmith = Child("Mary", "Smith", new DateTime(AGE13),false,false,true)
  val JennySmith = Child("Jenny", "Smith", new DateTime(AGE17),false,false,true)

  val address =  uk.gov.hmrc.personalincome.domain.userdata.Address("addressLine1", "addressLine2", Some("addressLine3"), Some("addressLine4"), "postcode")

  val personalDetails = PersonalDetails("firstname",
    "surname",
    TaxCreditsNino(nino.value),
    address,
    None, None, None, None)

  val partnerDetails = PartnerDetails("forename",
    Some("othernames"),
    "surname",
    TaxCreditsNino(nino.value),
    address,
    None,
    None,
    None,
    None)

  val children = Children(Seq(SarahSmith, JosephSmith, MarySmith))
  val taxRenewalSummary = TaxCreditSummary(paymentSummary, personalDetails, Some(partnerDetails), Children(Seq(SarahSmith, JosephSmith, MarySmith, JennySmith)))
  val taxRenewalSummaryWithoutChildrenOver16 = TaxCreditSummary(paymentSummary, personalDetails, Some(partnerDetails), children)

  val acceptHeader = "Accept" -> "application/vnd.hmrc.1.0+json"
  val emptyRequest = FakeRequest()
  val renewalJsonBody: JsValue = Json.toJson(renewal)

  def fakeRequest(body:JsValue) = FakeRequest(POST, "url").withBody(body)
    .withHeaders("Content-Type" -> "application/json")

  val emptyRequestWithAcceptHeader = FakeRequest().withHeaders(acceptHeader)

  def basicAuthString(encodedAuth:String): String = "Basic " + encodedAuth
  def encodedAuth(nino: Nino, tcrRenewalReference:RenewalReference): String = new String(Base64.encode(s"${nino.value}:${tcrRenewalReference.value}".getBytes))

  def emptyRequestWithAcceptHeaderAndAuthHeader(renewalsRef:RenewalReference) = FakeRequest().withHeaders(
    acceptHeader,
    HeaderKeys.tcrAuthToken -> basicAuthString(encodedAuth(nino, renewalsRef)))

  lazy val renewalBadRequest = fakeRequest(Json.toJson("Something Incorrect")).withHeaders(acceptHeader)

  lazy val jsonRenewalRequestWithNoAuthHeader = fakeRequest(renewalJsonBody).withHeaders(acceptHeader)

  lazy val jsonRenewalRequestWithAuthHeader = fakeRequest(renewalJsonBody).withHeaders(
    acceptHeader,
    HeaderKeys.tcrAuthToken -> "some-auth-token"
  )
  lazy val jsonRenewalRequestNoAcceptHeader = fakeRequest(renewalJsonBody)

  val authConnector = new TestAuthConnector(Some(nino))
  val taiConnector = new TestTaiConnector(Some(taxSummaryDetails))
  val tcrAuthToken = TcrAuthenticationToken("some-auth-token")
  val claimentDetails = ClaimantDetails(false, 1, "r", nino.value, None, false, "some-app-id")
  val ntcConnector = new TestNtcConnector(Success(200), Some(tcrAuthToken), claimentDetails)
  val ntcConnector400 = new TestNtcConnector(Success(200), None, claimentDetails)
  val exclusion = Exclusion(false)
  val exclusionResult = Json.parse("""{"showData":true}""")
  val taxCreditBrokerConnector = new TestTaxCreditBrokerConnector(paymentSummary, personalDetails, partnerDetails, Some(children), Some(exclusion))

  val testAccess = new TestAccessCheck(authConnector)
  val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector, taxCreditBrokerConnector, MicroserviceAuditConnector)

  val testSandboxPersonalIncomeService = SandboxPersonalIncomeService
  val sandboxCompositeAction = AccountAccessControlCheckOff
}

trait Success extends Setup {
  val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }
}

trait Generate_503 extends Setup {
  override val taiConnector = new TestTaiConnector(None)

  override val taxCreditBrokerConnector = new TestTaxCreditBrokerConnector(paymentSummary, personalDetails, partnerDetails, None, None)

  override val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector, taxCreditBrokerConnector, MicroserviceAuditConnector)
  val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }
}

trait NotFound extends Setup {
  override val taiConnector = new TestTaiConnector(None)
  override val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector, taxCreditBrokerConnector, MicroserviceAuditConnector)
  val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }
}

trait SuccessExcluded extends Setup {
  override val taxCreditBrokerConnector = new TestTaxCreditBrokerConnector(paymentSummary, personalDetails, partnerDetails, Some(children), Some(Exclusion(true)))

  override val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector, taxCreditBrokerConnector, MicroserviceAuditConnector)

  val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }
}

trait GateKeeper extends Setup {
  override val taiConnector = new TestTaiConnector(Some(taxSummaryDetails.copy(gateKeeper=Some(GateKeeper(true, List.empty)))))
  override val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector, taxCreditBrokerConnector, MicroserviceAuditConnector)

  val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }
}

trait AuthWithLowCL extends Setup {
  val routeToIv = true
  val routeToTwoFactor = false

  override val authConnector = new TestAuthConnector(None) {
    lazy val exception = new AccountWithLowCL("Forbidden to access since low CL")

    override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] = Future.failed(exception)
  }

  override val testAccess = new TestAccessCheck(authConnector)
  override val taiConnector = new TestTaiConnector(Some(taxSummaryDetails))
  override val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  override val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector, taxCreditBrokerConnector, MicroserviceAuditConnector)

  val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }

}

trait AuthWithoutNino extends Setup {

  override val authConnector =  new TestAuthConnector(None) {
    lazy val exception = new NinoNotFoundOnAccount("NINO not found!")

    override def grantAccess()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] = Future.failed(exception)
  }

  override val testAccess = new TestAccessCheck(authConnector)
  override val taiConnector = new TestTaiConnector(Some(taxSummaryDetails))
  override val testCompositeAction = new TestAccountAccessControlWithAccept(testAccess)
  override val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector, taxCreditBrokerConnector, MicroserviceAuditConnector)

  val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }
}

trait Ntc400Result extends Success {

  override val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector400, taxCreditBrokerConnector, MicroserviceAuditConnector)

  override val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }
}

trait Ntc400ResultExclusionTrue extends Success {

  override val exclusion = Exclusion(true)

  override val testPersonalIncomeService = new TestPersonalIncomeService(taiConnector, authConnector, ntcConnector400, taxCreditBrokerConnector, MicroserviceAuditConnector)

  override val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = testCompositeAction
  }
}

trait SandboxSuccess extends Setup {
  val controller = new PersonalIncomeController {
    override val service: PersonalIncomeService = testSandboxPersonalIncomeService
    override val accessControl: AccountAccessControlWithHeaderCheck = sandboxCompositeAction
  }
}
