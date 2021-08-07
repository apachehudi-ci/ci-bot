package com.ververica;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ververica.ci.CiActions;
import com.ververica.ci.CiActionsLookup;
import com.ververica.ci.CiProvider;
import com.ververica.github.GitHubCheckerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ververica.github.GitHubCheckerStatus.State.PENDING;
import static com.ververica.github.GitHubCheckerStatus.State.UNKNOWN;

public class CiReport {

	private static final Logger LOG = LoggerFactory.getLogger(CiReport.class);

	private static final Path COMMIT_BLACK_LIST_PATH = Paths.get("commit_blacklist.txt");
	private static Collection<String> commitBlackList = Collections.emptyList();

	@Deprecated
	private static final String REGEX_GROUP_COMMIT_HASH = "CommitHash";
	@Deprecated
	private static final String REGEX_GROUP_BUILD_STATUS = "BuildStatus";
	@Deprecated
	private static final String REGEX_GROUP_BUILD_URL = "URL";
	@Deprecated
	private static final String REGEX_GROUP_BUILD_TRIGGER_TYPE = "TriggerType";
	@Deprecated
	private static final String REGEX_GROUP_BUILD_TRIGGER_ID = "TriggerID";
	private static final String REGEX_GROUP_META_DATA = "MetaData";
	private static final String REGEX_GROUP_USER_DATA = "UserData";
	private static final String TEMPLATE_CI_REPORT_META_DATA_SECTION = "" +
			"<!--\n" +
			"Meta data\n" +
			"%s" +
			"-->";

	private static final String TEMPLATE_CI_REPORT_USER_DATA_SECTION = "" +
			"## CI report:\n" +
			"\n" +
			"%s";

	private static final String TEMPLATE_CI_REPORT_COMMAND_HELP_SECTION = "" +
			"<details>\n" +
			"<summary>Bot commands</summary>\n" +
			"  @hudi-bot supports the following commands:\n" +
			"\n" +
			" - `@hudi-bot run azure` re-run the last Azure build\n" +
			"</details>";

	private static final String TEMPLATE_CI_REPORT_DATA_SECTION = "" +
			TEMPLATE_CI_REPORT_META_DATA_SECTION +
			"\n" +
			TEMPLATE_CI_REPORT_USER_DATA_SECTION;

	private static final String TEMPLATE_CI_REPORT = "" +
			TEMPLATE_CI_REPORT_DATA_SECTION +
			"\n" +
			TEMPLATE_CI_REPORT_COMMAND_HELP_SECTION;

	private static final Pattern REGEX_PATTERN_CI_REPORT = Pattern.compile(
			String.format(escapeRegex(TEMPLATE_CI_REPORT_DATA_SECTION),
					"(?<" + REGEX_GROUP_META_DATA + ">.*)",
					"(?<" + REGEX_GROUP_USER_DATA + ">.*)")
					+ "(\n" + TEMPLATE_CI_REPORT_COMMAND_HELP_SECTION + ")?",
			Pattern.DOTALL);

	@Deprecated
	private static final String TEMPLATE_META_DATA_LINE = "Hash:%s Status:%s URL:%s TriggerType:%s TriggerID:%s\n";

	@Deprecated
	private static final Pattern REGEX_PATTERN_META_DATA_LINES = Pattern.compile(String.format(escapeRegex(TEMPLATE_META_DATA_LINE),
			"(?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]+)",
			"(?<" + REGEX_GROUP_BUILD_STATUS + ">[A-Z]+)",
			"(?<" + REGEX_GROUP_BUILD_URL + ">.+)",
			"(?<" + REGEX_GROUP_BUILD_TRIGGER_TYPE + ">.+)",
			"(?<" + REGEX_GROUP_BUILD_TRIGGER_ID + ">.+)"));

	// bf9a31483c0d55c968f65b8ca4a11557f52de456 [Travis CI](https://travis-ci.com/flink-ci/flink/builds/138727067) FAILURE
	private static final String TEMPLATE_USER_DATA_LINE = "* %s %s\n";
	// Travis CI [FAILURE](https://travis-ci.com/flink-ci/flink/builds/138727067)
	private static final String TEMPLATE_USER_DATA_BUILD_ITEM = "%s: [%s](%s) ";

	private static final String UNKNOWN_URL = "TBD";

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
			.enable(SerializationFeature.INDENT_OUTPUT);

	private final int pullRequestID;
	private final Map<String, Build> builds;

	private CiReport(int pullRequestID, Map<String, Build> builds) {
		this.pullRequestID = pullRequestID;
		this.builds = builds;
	}

	public static CiReport empty(int pullRequestID) {
		return new CiReport(pullRequestID, new LinkedHashMap<>());
	}

	public static CiReport fromComment(int pullRequestID, String comment, CiActionsLookup ciActionsLookup) {
		loadCommitBlackList();

		final Map<String, Build> builds = new LinkedHashMap<>();

		final Matcher reportMatcher = REGEX_PATTERN_CI_REPORT.matcher(comment);
		if (!reportMatcher.matches()) {
			throw new IllegalArgumentException("Not a valid CiReport comment.");
		} else {
			final String rawMetaData = reportMatcher.group(REGEX_GROUP_META_DATA);

			final MetaData metaData;
			// legacy mode
			if (!rawMetaData.contains("{")) {
				final Matcher metaDataMatcher = REGEX_PATTERN_META_DATA_LINES.matcher(rawMetaData);
				metaDataMatcher.reset();
				final List<MetaDataEntry> metaDataEntries = new ArrayList<>();
				while (metaDataMatcher.find()) {
					final String commitHash = metaDataMatcher.group(REGEX_GROUP_COMMIT_HASH);
					final String status = metaDataMatcher.group(REGEX_GROUP_BUILD_STATUS);
					final String url = metaDataMatcher.group(REGEX_GROUP_BUILD_URL);
					final String triggerType = metaDataMatcher.group(REGEX_GROUP_BUILD_TRIGGER_TYPE);
					final String triggerID = metaDataMatcher.group(REGEX_GROUP_BUILD_TRIGGER_ID);

					metaDataEntries.add(new MetaDataEntry(
							commitHash,
							GitHubCheckerStatus.State.valueOf(status),
							url,
							triggerID,
							Trigger.Type.valueOf(triggerType)));
				}
				metaData = new MetaData(metaDataEntries);
			} else {
				try {
					metaData = OBJECT_MAPPER.readValue(rawMetaData, MetaData.class);
				} catch (IOException e) {
					LOG.error("Fatal error while parsing CI report.", e);
					return CiReport.empty(pullRequestID);
				}
			}

			metaData.getMetaDataEntries().stream()
					.filter(metaDataEntry -> !commitBlackList.contains(metaDataEntry.hash))
					.forEach(metaDataEntry -> {
				final String commitHash = metaDataEntry.getHash();
				final GitHubCheckerStatus.State state = metaDataEntry.getStatus();
				final String url = metaDataEntry.url;
				final Trigger.Type triggerType = metaDataEntry.getTriggerType();
				final String triggerID = metaDataEntry.getTriggerID();

				final CiProvider ciProvider = ciActionsLookup.getActionsForString(url).map(CiActions::getCiProvider).orElse(CiProvider.Unknown);

				final GitHubCheckerStatus gitHubCheckerStatus;
				if (state == UNKNOWN) {
					gitHubCheckerStatus = new GitHubCheckerStatus(
							UNKNOWN,
							"TBD",
							CiProvider.Unknown);
				} else {
					gitHubCheckerStatus = new GitHubCheckerStatus(
							state,
							url,
							ciProvider);
				}

				builds.put(commitHash + triggerType + triggerID + gitHubCheckerStatus.getDetailsUrl().hashCode(), new Build(
						pullRequestID,
						commitHash,
						Optional.of(gitHubCheckerStatus),
						new Trigger(triggerType, triggerID, "unknown")));
			});
		}
		return new CiReport(pullRequestID, builds);
	}

	public static boolean isCiReportComment(String comment) {
		return REGEX_PATTERN_CI_REPORT.matcher(comment).matches();
	}

	public void add(Build build) {
		if (pullRequestID != build.pullRequestID) {
			throw new IllegalArgumentException();
		}

		final String baseHash = build.commitHash + build.trigger.getType().name() + build.trigger.getId();

		builds.remove(baseHash);
		builds.remove(baseHash + UNKNOWN_URL.hashCode());
		builds.put(
				baseHash + build.status.map(gitHubCheckerStatus -> String.valueOf(gitHubCheckerStatus.getDetailsUrl().hashCode())).orElse(""),
				build
		);
	}

	public Stream<Build> getBuilds() {
		return builds.values().stream();
	}

	public Stream<Build> getRequiredBuilds() {
		return getBuilds().filter(build -> !build.status.isPresent());
	}

	public Stream<Build> getUnknownBuilds() {
		return filterByStates(UNKNOWN);
	}

	public Stream<Build> getPendingBuilds() {
		return filterByStates(PENDING);
	}

	public Stream<Build> getFinishedBuilds() {
		return filterByStates(
				GitHubCheckerStatus.State.SUCCESS,
				GitHubCheckerStatus.State.FAILURE,
				GitHubCheckerStatus.State.CANCELED);
	}

	private Stream<Build> filterByStates(GitHubCheckerStatus.State state, GitHubCheckerStatus.State ... states) {
		EnumSet<GitHubCheckerStatus.State> targetStates = EnumSet.of(state, states);
		return getBuilds()
				.filter(b -> b.status.isPresent())
				.filter(b -> targetStates.contains(b.status.get().getState()));
	}


	public int getPullRequestID() {
		return pullRequestID;
	}

	private static void loadCommitBlackList() {
		if (Files.exists(COMMIT_BLACK_LIST_PATH)) {
			try {
				commitBlackList = Files.readAllLines(COMMIT_BLACK_LIST_PATH);
			} catch (IOException e) {
				LOG.warn("Could not read commit blacklist.", e);
			}
		}
	}

	@Override
	public String toString() {
		return String.format(TEMPLATE_CI_REPORT, createMetaDataSection(), createUserDataSection());
	}

	private String createMetaDataSection() {
		final MetaData metaData = new MetaData(builds.values().stream()
				.map(build -> {
					final GitHubCheckerStatus.State status;
					final String url;
					if (build.status.isPresent()) {
						status = build.status.get().getState();
						url = build.status.get().getDetailsUrl();
					} else {
						status = UNKNOWN;
						url = UNKNOWN_URL;
					}
					return new MetaDataEntry(build.commitHash, status, url, build.trigger.getId(), build.trigger.getType());
				})
				.collect(Collectors.toList()));

		try {
			return OBJECT_MAPPER.writeValueAsString(metaData);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Error while assembling meta data JSON.", e);
		}
	}

	private String createUserDataSection() {
		final Map<String, List<Build>> buildsPerHash = builds.values().stream()
				.filter(build -> build.status.isPresent())
				.collect(Collectors.groupingBy(
						build -> build.commitHash,
						LinkedHashMap::new,
						Collectors.toList()));

		final Map<String, String> reportEntryPerHash = new LinkedHashMap<>();
		buildsPerHash.forEach((hash, builds) -> {
			// reverse list so that distinct() retains the LAST matching build
			Collections.reverse(builds);
			builds = builds.stream().map(BuildDeduplicator::new).distinct().map(BuildDeduplicator::getOriginalBuild).collect(Collectors.toList());
			Collections.reverse(builds);
			builds.sort(Comparator.comparing(build -> build.status.map(GitHubCheckerStatus::getCiProvider).orElse(CiProvider.Unknown)));

			final StringBuilder reportEntryBuilder = new StringBuilder();
			for (Build build : builds) {
				if (build.status.isPresent()) {
					GitHubCheckerStatus status = build.status.get();
					if (status.getState() == GitHubCheckerStatus.State.DELETED) {
						return;
					}
					if (status.getState() != UNKNOWN) {
						reportEntryBuilder.append(
								String.format(
										TEMPLATE_USER_DATA_BUILD_ITEM,
										status.getCiProvider().getName(),
										status.getState().name(),
										status.getDetailsUrl()));
					}
				}
			}

			if (reportEntryBuilder.length() == 0) {
				reportEntryBuilder.append(UNKNOWN.name());
			}

			reportEntryPerHash.put(
					hash,
					String.format(
							TEMPLATE_USER_DATA_LINE,
							hash,
							reportEntryBuilder.toString()));
		});

		final StringBuilder userDataSectionBuilder = new StringBuilder();
		reportEntryPerHash.values().forEach(userDataSectionBuilder::append);

		return userDataSectionBuilder.toString();
	}

	private static class BuildDeduplicator {

		private final Build originalBuild;

		private BuildDeduplicator(Build originalBuild) {
			this.originalBuild = originalBuild;
		}

		public Build getOriginalBuild() {
			return originalBuild;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			BuildDeduplicator that = (BuildDeduplicator) o;
			// equal impl
			return Objects.equals(originalBuild.commitHash, that.originalBuild.commitHash) &&
					Objects.equals(
							originalBuild.status.map(GitHubCheckerStatus::getDetailsUrl).orElse(null),
							that.originalBuild.status.map(GitHubCheckerStatus::getDetailsUrl).orElse(null));
		}

		@Override
		public int hashCode() {
			return Objects.hash(originalBuild);
		}
	}

	private static String escapeRegex(String format) {
		return format
				.replaceAll("\\[", "\\\\[")
				.replaceAll("\\(", "\\\\(")
				.replaceAll("\\)", "\\\\)")
				.replaceAll("\\*", "\\\\*")
				// line-endings are standardized in GitHub comments
				.replaceAll("\n", "(\\\\r\\\\n|\\\\n|\\\\r)");
	}

	private static class MetaData {
		private final List<MetaDataEntry> metaDataEntries;
		private final int version;

		public MetaData(List<MetaDataEntry> metaDataEntries) {
			this(1, metaDataEntries);
		}

		private MetaData(
				@JsonProperty("version") int version,
				@JsonProperty("metaDataEntries") List<MetaDataEntry> metaDataEntries) {
			this.version = version;
			this.metaDataEntries = metaDataEntries;
		}

		public int getVersion() {
			return version;
		}

		public List<MetaDataEntry> getMetaDataEntries() {
			return metaDataEntries;
		}
	}

	private static class MetaDataEntry {
		private final String hash;
		private final GitHubCheckerStatus.State status;
		private final String url;
		private final String triggerID;
		private final Trigger.Type triggerType;

		@JsonCreator
		public MetaDataEntry(
				@JsonProperty("hash") String hash,
				@JsonProperty("status") GitHubCheckerStatus.State status,
				@JsonProperty("url") String url,
				@JsonProperty("triggerID") String triggerID,
				@JsonProperty("triggerType") Trigger.Type triggerType) {
			this.hash = hash;
			this.status = status;
			this.url = url;
			this.triggerID = triggerID;
			this.triggerType = triggerType;
		}

		public String getHash() {
			return hash;
		}

		public GitHubCheckerStatus.State getStatus() {
			return status;
		}

		public String getUrl() {
			return url;
		}

		public String getTriggerID() {
			return triggerID;
		}

		public Trigger.Type getTriggerType() {
			return triggerType;
		}
	}
}
