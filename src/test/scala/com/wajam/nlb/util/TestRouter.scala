package com.wajam.nlb.util

import com.typesafe.config._
import org.scalatest.{PrivateMethodTester, BeforeAndAfter, FunSuite}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import scala.util.matching.Regex
import com.wajam.nlb.NlbConfiguration

@RunWith(classOf[JUnitRunner])
class TestRouter extends FunSuite with BeforeAndAfter with PrivateMethodTester {

  val config = new NlbConfiguration(ConfigFactory.load())

  val router = new Router(Nil,
                          config.getZookeeperServers,
                          config.getResolvingService,
                          config.getNodeHttpPort,
                          config.getLocalNodePort)

  val fixture = {
    new {
      val samplePathList = List(
        "foo/:bar",
        "bar/:foo/foo"
      )
      val samplePathListWithDuplicates = List(
        "foo/:bar",
        "bar/:foo/foo",
        "foo/:foo",
        "bar/:foo/bar"
      )
    }
  }

  test("should pre-parse configured paths into regex") {
    val getMatchers = PrivateMethod[List[Regex]]('getMatchers)

    router invokePrivate getMatchers(fixture.samplePathList) map(_.toString) should equal(List(
        """foo/(\w+)(\?.+)?$""",
        """bar/(\w+)/.+"""
      ))
  }

  test("shoud eliminate duplicates when parsing paths") {
    val getMatchers = PrivateMethod[List[Regex]]('getMatchers)

    router invokePrivate getMatchers(fixture.samplePathListWithDuplicates) should have length 2
  }

  test("should extract id from /foo/id") {
    val getMatchers = PrivateMethod[List[Regex]]('getMatchers)
    val getId = PrivateMethod[Option[String]]('getId)

    val matchers = router invokePrivate getMatchers(fixture.samplePathList)

    router invokePrivate getId("foo/id", matchers) should equal(Some("id"))
  }

  test("should extract id from /foo/id?param=value&otherparam=othervalue") {
    val getMatchers = PrivateMethod[List[Regex]]('getMatchers)
    val getId = PrivateMethod[Option[String]]('getId)

    val matchers = router invokePrivate getMatchers(fixture.samplePathList)

    router invokePrivate getId("foo/id?param=value", matchers) should equal(Some("id"))
  }

  test("should extract id from /bar/id/foo") {
    val getMatchers = PrivateMethod[List[Regex]]('getMatchers)
    val getId = PrivateMethod[Option[String]]('getId)

    val matchers = router invokePrivate getMatchers(fixture.samplePathList)

    router invokePrivate getId("bar/id/foo", matchers) should equal(Some("id"))
  }

  test("shouldn't extract anything from /foo/id/bar") {
    val getMatchers = PrivateMethod[List[Regex]]('getMatchers)
    val getId = PrivateMethod[Option[String]]('getId)

    val matchers = router invokePrivate getMatchers(fixture.samplePathList)

    router invokePrivate getId("foo/id/bar", matchers) should equal(None)
  }

}
