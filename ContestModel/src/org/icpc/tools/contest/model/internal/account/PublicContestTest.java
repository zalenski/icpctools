package org.icpc.tools.contest.model.internal.account;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.icpc.tools.contest.model.IAccount;
import org.icpc.tools.contest.model.IClarification;
import org.icpc.tools.contest.model.IContestListener;
import org.icpc.tools.contest.model.IContestObject;
import org.icpc.tools.contest.model.IContestObject.ContestType;
import org.icpc.tools.contest.model.feed.ContestAPIHelper;
import org.icpc.tools.contest.model.feed.NDJSONFeedParser;
import org.icpc.tools.contest.model.internal.Account;
import org.icpc.tools.contest.model.internal.Clarification;
import org.icpc.tools.contest.model.internal.Contest;
import org.icpc.tools.contest.model.internal.Deletion;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static com.google.common.truth.Truth.assertThat;

/**
 * Visibility of clarification reply_to_id. A private question answered in public must not leave a
 * dangling reply_to_id for accounts that cannot see the question.
 */
@RunWith(JUnit4.class)
public class PublicContestTest {
	private static Clarification clar(String... kv) {
		Clarification c = new Clarification();
		for (int i = 0; i < kv.length; i += 2)
			c.add(kv[i], kv[i + 1]);
		return c;
	}

	private static Account account(String type, String teamId) {
		Account account = new Account();
		account.add("id", type + "-" + teamId);
		account.add("username", type + "-" + teamId);
		account.add("type", type);
		if (teamId != null)
			account.add("team_id", teamId);
		return account;
	}

	private static final class RoleViews {
		final Contest master = new Contest();
		final PublicContest pub = new PublicContest();
		final Contest analyst = AccountHelper.createAccountContest(account(IAccount.ANALYST, null));
		final TeamContest teamA = (TeamContest) AccountHelper.createAccountContest(account(IAccount.TEAM, "A"));
		final TeamContest teamB = (TeamContest) AccountHelper.createAccountContest(account(IAccount.TEAM, "B"));
		final List<String> publicEvents = new ArrayList<>();
		final List<String> teamAEvents = new ArrayList<>();

		RoleViews() {
			master.addListenerFromStart((contest, obj, delta) -> {
				pub.add(obj);
				analyst.add(obj);
				teamA.add(obj);
				teamB.add(obj);
			});
			pub.addListener((contest, obj, delta) -> {
				if (obj.getType() == ContestType.CLARIFICATION)
					publicEvents.add(event(delta, obj));
			});
			teamA.addListener((contest, obj, delta) -> {
				if (obj.getType() == ContestType.CLARIFICATION)
					teamAEvents.add(event(delta, obj));
			});
		}

		void add(IContestObject obj) {
			master.add(obj);
		}
	}

	private static void assertReplyHidden(IClarification clar) {
		assertThat(clar.getReplyToId()).isNull();
		for (String key : clar.getProperties().keySet())
			assertThat(key).isNotEqualTo("reply_to_id");
	}

	private static String event(IContestListener.Delta delta, IContestObject obj) {
		String replyTo = null;
		if (obj instanceof IClarification)
			replyTo = ((IClarification) obj).getReplyToId();
		return delta + " " + obj.getId() + " reply_to_id=" + replyTo;
	}

	@Test
	public void broadcastReplyToPrivateQuestionStripsReplyToForPublicAccounts() {
		RoleViews views = new RoleViews();
		views.add(clar("id", "q1", "from_team_id", "A", "text", "Is sample correct?", "time",
				"2026-06-16T11:14:10.100-04", "contest_time", "1:00:00.000"));
		views.add(clar("id", "r1", "reply_to_id", "q1", "text", "Yes, see the statement.", "time",
				"2026-06-16T11:14:30.100-04", "contest_time", "1:00:20.000"));

		assertThat(views.pub.getClarificationById("q1")).isNull();
		assertThat(views.analyst.getClarificationById("q1")).isNull();
		assertThat(views.teamB.getClarificationById("q1")).isNull();

		assertReplyHidden(views.pub.getClarificationById("r1"));
		assertReplyHidden(views.analyst.getClarificationById("r1"));
		assertReplyHidden(views.teamB.getClarificationById("r1"));

		// The team that asked can still see the link, and the source contest is unchanged.
		assertThat(views.teamA.getClarificationById("q1")).isNotNull();
		assertThat(views.teamA.getClarificationById("r1").getReplyToId()).isEqualTo("q1");
		assertThat(views.master.getClarificationById("r1").getReplyToId()).isEqualTo("q1");
		assertThat(views.pub.getClarificationById("r1")).isNotSameInstanceAs(views.master.getClarificationById("r1"));

		assertThat(views.publicEvents).containsExactly("ADD r1 reply_to_id=null");
	}

	@Test
	public void askingTeamRegainsReplyToWhenQuestionArrivesAfterTheBroadcast() {
		RoleViews views = new RoleViews();
		views.add(clar("id", "r1", "reply_to_id", "q1", "text", "Answered first.", "time", "2026-06-16T11:14:30.100-04",
				"contest_time", "1:00:20.000"));
		views.add(clar("id", "q1", "from_team_id", "A", "text", "Question second.", "time", "2026-06-16T11:14:10.100-04",
				"contest_time", "1:00:00.000"));

		assertThat(views.teamA.getClarificationById("r1").getReplyToId()).isEqualTo("q1");
		assertThat(views.pub.getClarificationById("q1")).isNull();
		assertThat(views.pub.getClarificationById("r1").getReplyToId()).isNull();
		assertThat(views.teamB.getClarificationById("r1").getReplyToId()).isNull();

		// The link is not published until the question itself is visible.
		assertThat(views.teamAEvents).containsExactly("ADD r1 reply_to_id=null", "ADD q1 reply_to_id=null",
				"UPDATE r1 reply_to_id=q1").inOrder();
		assertThat(views.publicEvents).containsExactly("ADD r1 reply_to_id=null");
	}

	@Test
	public void extraRecipientsDoNotSeeReplyToOfAPrivateQuestion() {
		RoleViews views = new RoleViews();
		Clarification reply = clar("id", "r2", "reply_to_id", "q1", "text", "Sent to more teams.", "time",
				"2026-06-16T11:15:30.100-04", "contest_time", "1:01:20.000");
		reply.add("to_team_ids", new String[] { "A", "B" });

		views.add(clar("id", "q1", "from_team_id", "A", "text", "Private.", "time", "2026-06-16T11:14:10.100-04",
				"contest_time", "1:00:00.000"));
		views.add(reply);

		assertThat(views.pub.getClarificationById("r2")).isNull();
		assertThat(views.teamA.getClarificationById("r2").getReplyToId()).isEqualTo("q1");
		assertThat(views.teamB.getClarificationById("r2")).isNotNull();
		assertReplyHidden(views.teamB.getClarificationById("r2"));
		assertThat(views.teamB.getClarificationById("r2").getToTeamIds()).asList().containsExactly("A", "B").inOrder();
	}

	@Test
	public void deletingTheQuestionRemovesReplyToBeforeTheDeleteEvent() {
		RoleViews views = new RoleViews();
		views.add(clar("id", "q1", "from_team_id", "A", "text", "Question.", "time", "2026-06-16T11:14:10.100-04",
				"contest_time", "1:00:00.000"));
		views.add(clar("id", "r1", "reply_to_id", "q1", "text", "Broadcast.", "time", "2026-06-16T11:14:30.100-04",
				"contest_time", "1:00:20.000"));
		views.add(new Deletion("q1", ContestType.CLARIFICATION));

		assertThat(views.teamA.getClarificationById("q1")).isNull();
		assertThat(views.teamA.getClarificationById("r1").getReplyToId()).isNull();
		assertThat(views.pub.getClarificationById("r1").getReplyToId()).isNull();
		// Filtering must not edit the source object.
		assertThat(views.master.getClarificationById("r1").getReplyToId()).isEqualTo("q1");

		assertThat(views.teamAEvents).containsExactly("ADD q1 reply_to_id=null", "ADD r1 reply_to_id=q1",
				"UPDATE r1 reply_to_id=null", "DELETE q1 reply_to_id=null").inOrder();
	}

	@Test
	public void inPlaceEditOfSourceReplyDoesNotLeakIntoThePublicContest() {
		RoleViews views = new RoleViews();
		views.add(clar("id", "qPublic", "text", "Broadcast question.", "time", "2026-06-16T11:14:10.100-04",
				"contest_time", "1:00:00.000"));
		views.add(clar("id", "r1", "reply_to_id", "qPublic", "text", "Reply.", "time", "2026-06-16T11:14:30.100-04",
				"contest_time", "1:00:20.000"));

		assertThat(views.pub.getClarificationById("r1").getReplyToId()).isEqualTo("qPublic");
		assertThat(views.pub.getClarificationById("r1")).isNotSameInstanceAs(views.master.getClarificationById("r1"));

		((Clarification) views.master.getClarificationById("r1")).add("reply_to_id", "hidden-question");
		views.add(views.master.getClarificationById("r1"));

		assertThat(views.pub.getClarificationById("r1").getReplyToId()).isEqualTo("qPublic");

		views.add(clar("id", "r1", "reply_to_id", "hidden-question", "text", "Edited.", "time",
				"2026-06-16T11:14:40.100-04", "contest_time", "1:00:30.000"));
		assertThat(views.pub.getClarificationById("r1").getText()).isEqualTo("Edited.");
		assertReplyHidden(views.pub.getClarificationById("r1"));
	}

	@Test
	public void contestApi2023BroadcastFeedStripsReplyTo() throws Exception {
		ContestAPIHelper.setVersion(ContestAPIHelper.SpecVersion.v2023_06);
		try {
			RoleViews views = new RoleViews();
			String feed = "{\"type\":\"clarifications\",\"id\":\"q1\",\"data\":{\"id\":\"q1\",\"from_team_id\":\"A\",\"to_team_id\":null,\"reply_to_id\":null,\"text\":\"private?\",\"time\":\"2026-06-16T11:14:10.100-04\",\"contest_time\":\"1:00:00.000\"}}\n"
					+ "{\"type\":\"clarifications\",\"id\":\"r1\",\"data\":{\"id\":\"r1\",\"from_team_id\":null,\"to_team_id\":null,\"reply_to_id\":\"q1\",\"text\":\"public answer\",\"time\":\"2026-06-16T11:14:30.100-04\",\"contest_time\":\"1:00:20.000\"}}\n";
			new NDJSONFeedParser().parse(views.master,
					new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8)));

			assertThat(views.master.getClarificationById("r1").isBroadcast()).isTrue();
			assertThat(views.pub.getClarificationById("q1")).isNull();
			assertThat(views.pub.getClarificationById("r1").getReplyToId()).isNull();
			assertThat(views.teamA.getClarificationById("r1").getReplyToId()).isEqualTo("q1");
			assertThat(views.teamB.getClarificationById("r1").getReplyToId()).isNull();
		} finally {
			ContestAPIHelper.setVersion(null);
		}
	}
}
