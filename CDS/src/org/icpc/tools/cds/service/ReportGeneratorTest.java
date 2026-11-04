package org.icpc.tools.cds.service;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.icpc.tools.contest.model.IStanding;
import org.icpc.tools.contest.model.ITeam;
import org.icpc.tools.contest.model.feed.JSONEncoder;
import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.internal.Contest;
import org.icpc.tools.contest.model.internal.ContestObject;
import org.icpc.tools.contest.model.internal.Info;
import org.icpc.tools.contest.model.internal.Judgement;
import org.icpc.tools.contest.model.internal.JudgementType;
import org.icpc.tools.contest.model.internal.Language;
import org.icpc.tools.contest.model.internal.Problem;
import org.icpc.tools.contest.model.internal.Run;
import org.icpc.tools.contest.model.internal.Submission;
import org.icpc.tools.contest.model.internal.Team;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ReportGeneratorTest {
	@Test
	public void testHiddenTeamsAreExcludedLikeTheScoreboard() {
		Contest contest = contestWithHiddenTeam();

		ITeam[] scoreboard = contest.getOrderedTeams();
		assertEquals(1, scoreboard.length);
		assertEquals("1", scoreboard[0].getId());
		IStanding standing = contest.getStanding(scoreboard[0]);
		assertEquals(1, standing.getNumSolved());

		JsonObject problemA = row(report(contest, ReportGenerator.ReportType.PROBLEMS), "A");
		assertEquals(1, problemA.getInt("AC"));
		assertEquals(1, problemA.getInt("WA"));
		assertEquals(1, problemA.getInt("solved"));
		assertEquals(1, problemA.getInt("failed"));
		assertEquals(2, problemA.getInt("total"));

		// Only the hidden team solved B, so it must not show up as an accepted solution.
		JsonObject problemB = row(report(contest, ReportGenerator.ReportType.PROBLEMS), "B");
		assertEquals(0, problemB.getInt("AC"));
		assertEquals(0, problemB.getInt("solved"));
		assertEquals(0, problemB.getInt("total"));

		JsonObject java = row(report(contest, ReportGenerator.ReportType.LANGS), "java");
		assertEquals(1, java.getInt("solved"));
		assertEquals(1, java.getInt("failed"));
		assertEquals(2, java.getInt("total"));

		JsonObject python = row(report(contest, ReportGenerator.ReportType.LANGS), "python");
		assertEquals(0, python.getInt("solved"));
		assertEquals(0, python.getInt("total"));

		JsonObject accepted = row(report(contest, ReportGenerator.ReportType.RUNS), "AC");
		JsonObject rejected = row(report(contest, ReportGenerator.ReportType.RUNS), "WA");
		assertEquals(1, accepted.getInt("total"));
		assertEquals(1, rejected.getInt("total"));
	}

	@Test
	public void testRunWithNoSubmissionIsStillCounted() {
		Contest contest = contestWithHiddenTeam();
		Run orphan = new Run();
		orphan.add("id", "orphan");
		orphan.add("judgement_type_id", "AC");
		contest.add(orphan);

		JsonObject accepted = row(report(contest, ReportGenerator.ReportType.RUNS), "AC");
		assertEquals(2, accepted.getInt("total"));
	}

	private static Contest contestWithHiddenTeam() {
		Contest contest = new Contest();
		contest.add(info());
		contest.add(judgementType("AC", "Accepted", true, false));
		contest.add(judgementType("WA", "Wrong Answer", false, true));
		contest.add(language("java", "Java"));
		contest.add(language("python", "Python"));
		contest.add(problem("A", "Problem A"));
		contest.add(problem("B", "Problem B"));
		contest.add(team("1", "Contestants", false));
		contest.add(team("jury", "Jury", true));

		addSubmission(contest, "s1", "1", "A", "java", "AC", "0:10:00");
		addSubmission(contest, "s2", "1", "A", "java", "WA", "0:20:00");
		addSubmission(contest, "s3", "jury", "A", "python", "AC", "0:30:00");
		addSubmission(contest, "s4", "jury", "B", "python", "AC", "0:40:00");
		return contest;
	}

	private static void addSubmission(Contest contest, String id, String teamId, String problemId, String languageId,
			String judgementTypeId, String contestTime) {
		Submission submission = new Submission();
		submission.add("id", id);
		submission.add("team_id", teamId);
		submission.add("problem_id", problemId);
		submission.add("language_id", languageId);
		submission.add("contest_time", contestTime);
		submission.add("time", "2024-01-01T01:00:00Z");
		contest.add(submission);

		Judgement judgement = new Judgement();
		judgement.add("id", "j-" + id);
		judgement.add("submission_id", id);
		judgement.add("judgement_type_id", judgementTypeId);
		judgement.add("start_contest_time", contestTime);
		judgement.add("start_time", "2024-01-01T01:00:00Z");
		contest.add(judgement);

		Run run = new Run();
		run.add("id", "r-" + id);
		run.add("judgement_id", "j-" + id);
		run.add("judgement_type_id", judgementTypeId);
		run.add("contest_time", contestTime);
		run.add("time", "2024-01-01T01:00:00Z");
		contest.add(run);
	}

	private static ContestObject info() {
		Info info = new Info();
		info.add("id", "contest");
		info.add("name", "Test");
		info.add("duration", "5:00:00");
		info.add("penalty_time", 20);
		return info;
	}

	private static ContestObject judgementType(String id, String name, boolean solved, boolean penalty) {
		JudgementType type = new JudgementType();
		type.add("id", id);
		type.add("name", name);
		type.add("solved", solved);
		type.add("penalty", penalty);
		return type;
	}

	private static ContestObject language(String id, String name) {
		Language language = new Language();
		language.add("id", id);
		language.add("name", name);
		return language;
	}

	private static ContestObject problem(String id, String name) {
		Problem problem = new Problem();
		problem.add("id", id);
		problem.add("name", name);
		problem.add("ordinal", 1);
		return problem;
	}

	private static ContestObject team(String id, String name, boolean hidden) {
		Team team = new Team();
		team.add("id", id);
		team.add("name", name);
		if (hidden)
			team.add("hidden", true);
		return team;
	}

	private static String report(Contest contest, ReportGenerator.ReportType type) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		ReportGenerator.report(pw, contest, type);
		pw.flush();
		return sw.toString();
	}

	private static JsonObject row(String json, String id) {
		Object[] rows = new JSONParser(json).readArray();
		for (Object row : rows) {
			JsonObject obj = (JsonObject) row;
			if (id.equals(obj.getString("id"))) {
				assertNotNull(obj);
				return obj;
			}
		}
		throw new AssertionError("No row with id " + id + " in " + json);
	}
}
