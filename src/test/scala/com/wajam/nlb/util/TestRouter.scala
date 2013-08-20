package com.wajam.nlb.util

import org.scalatest.{BeforeAndAfter, FunSuite}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._

@RunWith(classOf[JUnitRunner])
class TestRouter extends FunSuite with BeforeAndAfter {

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

    Router.getMatchers(fixture.samplePathList).map(_.toString) should equal(
      List(
        """foo/(\w+)(\?.+)?$""",
        """bar/(\w+)/.+"""
      )
    )
  }

  test("shoud eliminate duplicates when parsing paths") {
    Router.getMatchers(fixture.samplePathListWithDuplicates) should have length 2
  }

  test("should extract id from /foo/id") {
    val matchers = Router.getMatchers(fixture.samplePathList)

    Router.getId("foo/id", matchers) should equal(Some("id"))
  }

  test("should extract id from /foo/id?param=value&otherparam=othervalue") {
    val matchers = Router.getMatchers(fixture.samplePathList)

    Router.getId("foo/id?param=value", matchers) should equal(Some("id"))
  }

  test("should extract id from /bar/id/foo") {
    val matchers = Router.getMatchers(fixture.samplePathList)

    Router.getId("bar/id/foo", matchers) should equal(Some("id"))
  }

  test("shouldn't extract anything from /foo/id/bar") {
    val matchers = Router.getMatchers(fixture.samplePathList)

    Router.getId("foo/id/bar", matchers) should equal(None)
  }

}
