package com.monkeyboy.data

import java.util
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.apache.http.NameValuePair
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import org.apache.logging.log4j.scala.Logging

/**
 * Created by monkeyboy on 2020/4/27.
 */

object JenkinsClient extends Logging {

  var baseUrl: String = _
  var userName: String = _
  var passWd: String = _
  var jobName: String = _
  var branch: String = _
  var exitCode: AtomicInteger = new AtomicInteger(0)

  val IN_PROGRESS = "IN_PROGRESS"
  val PAUSED_PENDING_INPUT = "PAUSED_PENDING_INPUT"
  val SUCCESS = "SUCCESS"
  val FAILED = "FAILED"
  val ABORTED = "ABORTED"
  val NOT_EXECUTED = "NOT_EXECUTED"
  val UNSTABLE = "UNSTABLE"

  val requestConfig: RequestConfig =
    RequestConfig.custom
      .setConnectTimeout(5 * 1000)
      .setConnectionRequestTimeout(5 * 1000)
      .setSocketTimeout(5 * 1000)
      .build
  val defaultHttpClient =
    HttpClients
      .custom()
      .setDefaultRequestConfig(requestConfig)
      .build()

  val countDownLatch = new CountDownLatch(1)


  def getEnvOrProperty(key: String): String = {
    Option(System.getenv(key)).getOrElse(System.getProperty(key))
  }

  def main(args: Array[String]): Unit = {

    jobName = getEnvOrProperty("JOB_NAME")
    baseUrl = Option(getEnvOrProperty("JENKINS_URL")).getOrElse("http://localhost:8083")
    branch = Option(getEnvOrProperty("BRANCH")).getOrElse("main")
    userName = getEnvOrProperty("USERNAME")
    passWd = getEnvOrProperty("PASSWORD")
    logger.info(s"jobName: $jobName, branch: $branch ,baseUrl: $baseUrl, userName: $userName, passWd: $passWd")

    val beforeCurrentBuildNumber = getCurrentBuildNumber(jobName)
    logger.info(s"beforeCurrentBuildNumber: $beforeCurrentBuildNumber")

    // 因为触发build以后，不一定能立马增加build的count，所以走异步
    new Thread() {
      override def run(): Unit = {
        var nextBuildNo = getCurrentBuildNumber(jobName)
        while (nextBuildNo <= beforeCurrentBuildNumber) {
          nextBuildNo = getCurrentBuildNumber(jobName)
          Thread.sleep(5 * 1000L)
        }
        countDownLatch.countDown()
      }
    }.start()

    pipelineBuildFire(getPipelineBuildUrl(jobName))
    countDownLatch.await()
    val currentBuildNumber = getCurrentBuildNumber(jobName)
    logger.info("getCurrentBuildNumber: " + currentBuildNumber)

    jenkinsTrigger(jobName, currentBuildNumber)
    defaultHttpClient.close()

    System.exit(exitCode.get())
  }

  def pipelineBuildFire(url: String): String = {
    val authData = getAuthData
    val httpPost = new HttpPost(url.replace(" ", "%20"))
    httpPost.addHeader("Authorization", authData)
    httpPost.addHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
    val crumb = getCrumb
    if (crumb._1 != "error") {
      httpPost.addHeader(crumb._1, crumb._2)
    }
    val entity = defaultHttpClient.execute(httpPost).getEntity
    var content = ""
    if (null != entity) {
      val inputStream = entity.getContent
      content = Source.fromInputStream(inputStream).getLines.mkString
      inputStream.close()
    }
    content
  }

  def jenkinsTrigger(jobName: String, buildNo: Long): Unit = {

    val latestPipeline = getPipelineHistoryRuns(jobName).filter(_.get("id").textValue() == buildNo.toString).head
    val status = latestPipeline.get("status").textValue()
    status match {
      case IN_PROGRESS =>
        logger.info(s"$jobName buildNo: ${buildNo} is in IN_PROGRESS ,sleep 30 seconds")
        Thread.sleep(30 * 1000L)
        jenkinsTrigger(jobName, buildNo)

      case PAUSED_PENDING_INPUT =>
        logger.info(s"$jobName buildNo: ${buildNo} is in PAUSED_PENDING_INPUT")
        val pendingInputActionsUrl = latestPipeline.get("_links")
          .get("pendingInputActions")
          .get("href")
          .textValue()
        val finalInputActionsUrl = s"$baseUrl${pendingInputActionsUrl}"
        logger.info(s"finalInputActionsUrl: ${finalInputActionsUrl}")

        val inputMsg = getInputMessage(finalInputActionsUrl)
        logger.info(s"inputMsg: ${inputMsg}")

        val processUrl = getPendingInputActions(finalInputActionsUrl)
        val finalProcessUrl = s"${baseUrl}$processUrl"
        val result = postRequest(finalProcessUrl, getInputParameter(inputMsg, branch))
        logger.info(s"post $finalProcessUrl request result: ${result}")

        jenkinsTrigger(jobName, buildNo)

      case SUCCESS => logger.info(s"$jobName buildNo: ${buildNo} is SUCCESS")

      case FAILED =>
        logger.info(s"$jobName buildNo: ${buildNo} is FAILED")
        exitCode.getAndSet(1)

      case ABORTED =>
        logger.info(s"$jobName buildNo: ${buildNo} is ABORTED")
        exitCode.getAndSet(1)

      case NOT_EXECUTED => logger.info(s"$jobName buildNo: ${buildNo} is NOT_EXECUTED")

      case UNSTABLE => logger.info(s"$jobName buildNo: ${buildNo} is UNSTABLE")

      case _ =>
        logger.info(s"$jobName buildNo: ${buildNo} is UNKNOWN REASON")
        exitCode.getAndSet(1)
    }
  }

  def getAuthData(): String = {
    "Basic " + Base64.getEncoder.encodeToString((userName + ":" + passWd).getBytes)
  }

  /**
   * 线上机器不需要设置crumb
   *
   * @return
   */
  def getCrumb(): (String, String) = {
    try {
      val res = getRequest(s"$baseUrl/crumbIssuer/api/json")
      val json = new ObjectMapper()
        .readTree(res)
      (json.get("crumbRequestField").textValue(), json.get("crumb").textValue())
    }
    catch {
      case NonFatal(e) => logger.error(s"cruber: ${e}")
        ("error", "")
    }

  }

  def getInputParameter(inputMsg: String, branch: String): String = {
    if (null != inputMsg && inputMsg.toLowerCase.trim.contains("commitid")) {
      s"""
         |{"parameter": [{"name": "gitCommitId", "value": "$branch"}]}
    """.stripMargin
    } else {
      s"""
         |{"parameter": []}
       """.stripMargin
    }
  }

  def getPipelineBuildUrl(piplineName: String): String = {
    s"${baseUrl}/job/${piplineName}/build"
  }

  def getPipelineHistoryRunsUrl(piplineName: String): String = {
    s"$baseUrl/job/${piplineName}/wfapi/runs"
  }

  def getPipelineDescribeUrl(piplineName: String): String = {
    s"$baseUrl/job/${piplineName}/wfapi/describe"
  }

  def getPipelineHistoryRuns(piplineName: String): List[JsonNode] = {
    val res = getRequest(getPipelineHistoryRunsUrl(piplineName))
    new ObjectMapper()
      .readTree(res)
      .elements()
      .asScala.toList
  }

  def getCurrentBuildNumber(pipelineName: String): Long = {
    val res = getRequest(getPipelineDescribeUrl(pipelineName))
    new ObjectMapper()
      .readTree(res)
      .get("runCount")
      .asLong()
  }

  def getPendingInputActions(url: String): String = {
    val res = getRequest(url)
    new ObjectMapper()
      .readTree(res)
      .elements()
      .asScala.toList
      .head
      .get("proceedUrl")
      .textValue()
  }

  def getInputMessage(url: String): String = {
    val res = getRequest(url)
    new ObjectMapper()
      .readTree(res)
      .elements()
      .asScala.toList
      .head
      .get("message")
      .textValue()
  }

  def postRequest(url: String, params: String): String = {
    val authData = getAuthData
    val httpPost = new HttpPost(url.replace(" ", "%20"))
    httpPost.addHeader("Authorization", authData)
    httpPost.addHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
    val crumb = getCrumb
    if (crumb._1 != "crumb") {
      httpPost.addHeader(crumb._1, crumb._2)
    }
    val list = new util.ArrayList[NameValuePair]()
    list.add(new BasicNameValuePair("json", params))
    httpPost.setEntity(new UrlEncodedFormEntity(list))
    val entity = defaultHttpClient.execute(httpPost).getEntity
    var content = ""
    if (null != entity) {
      val inputStream = entity.getContent
      content = Source.fromInputStream(inputStream).getLines.mkString
      inputStream.close()
    }
    content
  }

  def getRequest(url: String): String = {
    val authData = getAuthData
    val httpGet = new HttpGet(url)
    httpGet.setHeader("Authorization", authData)
    val entity = defaultHttpClient.execute(httpGet).getEntity
    var content = ""
    if (null != entity) {
      val inputStream = entity.getContent
      content = Source.fromInputStream(inputStream).getLines.mkString
      inputStream.close()
    }
    content
  }
}
