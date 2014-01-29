package com.wajam.nlb.util

import org.scalatest.FlatSpec
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers._

@RunWith(classOf[JUnitRunner])
class TestRouter extends FlatSpec {

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

  "a router" should "pre-parse configured paths into regex" in {

    Router.getMatchers(samplePathList).map(_.toString) should equal(
      List(
        """foo/(\w+)(\?.+)?$""",
        """bar/(\w+)/.+"""
      )
    )
  }

  it should "eliminate duplicates when parsing paths" in {
    Router.getMatchers(samplePathListWithDuplicates) should have length 2
  }

  it should "extract id from /foo/id" in {
    val matchers = Router.getMatchers(samplePathList)

    Router.getId("foo/id", matchers) should equal(Some("id"))
  }

  it should "extract id from /foo/id?param=value&otherparam=othervalue" in {
    val matchers = Router.getMatchers(samplePathList)

    Router.getId("foo/id?param=value", matchers) should equal(Some("id"))
  }

  it should "extract id from /bar/id/foo" in {
    val matchers = Router.getMatchers(samplePathList)

    Router.getId("bar/id/foo", matchers) should equal(Some("id"))
  }

  it should "extract anything from /foo/id/bar" in {
    val matchers = Router.getMatchers(samplePathList)

    Router.getId("foo/id/bar", matchers) should equal(None)
  }
}
