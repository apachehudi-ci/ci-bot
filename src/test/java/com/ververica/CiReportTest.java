package com.ververica;

import com.ververica.github.GitHubCheckerStatus;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class CiReportTest {

	private static final String EMPTY_CI_REPORT = "" +
			"<!--\n" +
			"Meta data\n" +
			"-->\n" +
			"## CI report:\n" +
			"\n";

	@Deprecated
	private static final String LEGACY_CI_REPORT = "" +
			"<!--\n" +
			"Meta data\n" +
			"Hash:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 Status:SUCCESS URL:https://travis-ci.com/flink-ci/flink/builds/123348301 TriggerType:PUSH TriggerID:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3\n" +
			"-->\n" +
			"## CI report:\n" +
			"\n";

	@Deprecated
	private static final String LEGACY_CI_REPORT_WITH_MULTIPLE_BUILDS = "" +
			"<!--\n" +
			"Meta data\n" +
			"Hash:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 Status:SUCCESS URL:https://travis-ci.com/flink-ci/flink/builds/123348301 TriggerType:PUSH TriggerID:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3\n" +
			"Hash:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 Status:SUCCESS URL:https://travis-ci.com/flink-ci/flink/builds/123348302 TriggerType:MANUAL TriggerID:1\n" +
			"-->\n" +
			"## CI report:\n" +
			"\n";

	@Deprecated
	private static final String LEGACY_CI_REPORT_WITH_UNKNOWN_STATUS = "" +
			"<!--\n" +
			"Meta data\n" +
			"Hash:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 Status:UNKNOWN URL:TBD TriggerType:PUSH TriggerID:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3\n" +
			"-->\n" +
			"## CI report:\n" +
			"\n";

	private static final String CI_REPORT = "" +
			"<!--\n" +
			"Meta data\n" +
			"{\n" +
			"  \"version\" : 1,\n" +
			"  \"metaDataEntries\" : [ {\n" +
			"    \"hash\" : \"dede21b50da7c596b8754860aa5e4025d67f4961\",\n" +
			"    \"status\" : \"SUCCESS\",\n" +
			"    \"url\" : \"https://travis-ci.com/flink-ci/flink/builds/1234\",\n" +
			"    \"triggerID\" : \"dede21b50da7c596b8754860aa5e4025d67f4961\",\n" +
			"    \"triggerType\" : \"PUSH\"\n" +
			"  }, {\n" +
			"    \"hash\" : \"1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3\",\n" +
			"    \"status\" : \"UNKNOWN\",\n" +
			"    \"url\" : \"TBD\",\n" +
			"    \"triggerID\" : \"1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3\",\n" +
			"    \"triggerType\" : \"PUSH\"\n" +
			"  } ]\n" +
			"}-->\n" +
			"## CI report:\n" +
			"\n";

	@Test
	public void testLegacyParsing() {
		CiReport ciReport = CiReport.fromComment(1, LEGACY_CI_REPORT, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testEmptyParsing() {
		CiReport ciReport = CiReport.fromComment(1, EMPTY_CI_REPORT, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testLegacyParsingWithMultipleBuilds() {
		CiReport ciReport = CiReport.fromComment(1, LEGACY_CI_REPORT_WITH_MULTIPLE_BUILDS, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testLegacyParsingWithUnknownStatus() {
		CiReport ciReport = CiReport.fromComment(1, LEGACY_CI_REPORT_WITH_UNKNOWN_STATUS, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testParsing() {
		CiReport ciReport = CiReport.fromComment(1, CI_REPORT, s -> Optional.empty());
		Assert.assertEquals(2, ciReport.getBuilds().count());

		final Map<String, Build> entriesByTrigger = ciReport.getBuilds().collect(Collectors.toMap(entry -> entry.commitHash, entry -> entry));
		{
			final Build build = entriesByTrigger.get("dede21b50da7c596b8754860aa5e4025d67f4961");
			Assert.assertEquals(GitHubCheckerStatus.State.SUCCESS, build.status.get().getState());
			Assert.assertEquals("https://travis-ci.com/flink-ci/flink/builds/1234", build.status.get().getDetailsUrl());
			Assert.assertEquals("dede21b50da7c596b8754860aa5e4025d67f4961", build.trigger.getId());
			Assert.assertEquals(Trigger.Type.PUSH, build.trigger.getType());
		}
		{
			final Build build = entriesByTrigger.get("1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3");
			Assert.assertEquals(GitHubCheckerStatus.State.UNKNOWN, build.status.get().getState());
			Assert.assertEquals("TBD", build.status.get().getDetailsUrl());
			Assert.assertEquals("1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3", build.trigger.getId());
			Assert.assertEquals(Trigger.Type.PUSH, build.trigger.getType());
		}
	}
}
