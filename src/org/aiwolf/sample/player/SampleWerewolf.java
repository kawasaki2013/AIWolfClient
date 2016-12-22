/**
 * SampleWerewolf.java
 * 
 * Copyright (c) 2016 人狼知能プロジェクト
 */
package org.aiwolf.sample.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.aiwolf.client.lib.AgreeContentBuilder;
import org.aiwolf.client.lib.AttackContentBuilder;
import org.aiwolf.client.lib.ComingoutContentBuilder;
import org.aiwolf.client.lib.Content;
import org.aiwolf.client.lib.DisagreeContentBuilder;
import org.aiwolf.client.lib.DivineContentBuilder;
import org.aiwolf.client.lib.EstimateContentBuilder;
import org.aiwolf.client.lib.InquestContentBuilder;
import org.aiwolf.client.lib.TalkType;
import org.aiwolf.client.lib.Topic;
import org.aiwolf.client.lib.VoteContentBuilder;
import org.aiwolf.common.data.Agent;
import org.aiwolf.common.data.Judge;
import org.aiwolf.common.data.Role;
import org.aiwolf.common.data.Species;
import org.aiwolf.common.data.Talk;
import org.aiwolf.common.data.Vote;
import org.aiwolf.common.net.GameInfo;
import org.aiwolf.common.net.GameSetting;
import org.aiwolf.common.util.Counter;
import org.aiwolf.sample.lib.AbstractWerewolf;

/**
 * <div lang="ja">人狼プレイヤーのサンプル</div>
 *
 * <div lang="en">Sample werewolf agent</div>
 */
public class SampleWerewolf extends AbstractWerewolf {

	GameInfo currentGameInfo;
	int day;
	Agent me;
	Role myRole;
	AdditionalGameInfo agi;
	Agent voteCandidate; // 投票先候補
	Agent declaredVoteCandidate; // 宣言した投票先候補
	Agent attackCandidate; // 襲撃先候補
	Agent declaredAttackCandidate; // 宣言した襲撃先候補
	Vote lastVote; // 再投票における前回の投票
	Vote lastAttackVote; // 襲撃再投票における前回の投票
	Deque<Content> talkQueue = new LinkedList<>();
	Deque<Content> whisperQueue = new LinkedList<>();
	Agent possessed; // 裏切り者と思われるプレイヤー
	List<Agent> werewolves; // 人狼リスト
	List<Agent> humans; // 人間リスト

	int talkTurn; // talk()のターン
	int comingoutDay; // カミングアウトする日
	List<Integer> comingoutDays = new ArrayList<>(Arrays.asList(1, 2, 3));
	int comingoutTurn; // カミングアウトするターン
	List<Integer> comingoutTurns = new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4, 5));
	boolean isCameout; // カミングアウト済みか否か
	Deque<Judge> divinationQueue = new LinkedList<>(); // 偽占い結果のFIFO
	Deque<Judge> inquestQueue = new LinkedList<>(); // 偽霊媒結果のFIFO
	List<Agent> divinedAgents = new ArrayList<>(); // 偽占い済みエージェントのリスト
	Role fakeRole; // 騙る役職

	@Override
	public String getName() {
		return "SampleWerewolf";
	}

	@Override
	public void initialize(GameInfo gameInfo, GameSetting gameSetting) {
		me = gameInfo.getAgent();
		myRole = gameInfo.getRole();
		agi = new AdditionalGameInfo(gameInfo);
		werewolves = new ArrayList<>(gameInfo.getRoleMap().keySet());
		humans = new ArrayList<>(agi.getAliveOthers());
		humans.removeAll(werewolves);

		List<Role> fakeRoles = new ArrayList<>(Arrays.asList(Role.VILLAGER));
		for (Role role : gameInfo.getExistingRoles()) {
			if (role == Role.SEER || role == Role.MEDIUM) {
				fakeRoles.add(role);
			}
		}
		Collections.shuffle(fakeRoles);
		fakeRole = fakeRoles.get(0);
		enqueueWhisper(new Content(new ComingoutContentBuilder(me, fakeRole)));
		// 占い師か霊媒師なら1～3日目からランダム，村人ならカミングアウトなし
		if (fakeRole == Role.VILLAGER) {
			comingoutDay = 10000;
		} else {
			Collections.shuffle(comingoutDays);
			comingoutDay = comingoutDays.get(0);
			Collections.shuffle(comingoutTurns);
			comingoutTurn = comingoutTurns.get(0);
		}

		isCameout = false;
		inquestQueue.clear();
		divinationQueue.clear();
		divinedAgents.clear();
	}

	@Override
	public void dayStart() {
		// このメソッドの前に呼ばれるupdate()に任せて，何もしない
	}

	@Override
	public void update(GameInfo gameInfo) {

		// 1日の最初のupdate()でdayStart()の機能を代行する
		if (gameInfo.getDay() == day + 1) { // 1日の最初のupdate()
			day = gameInfo.getDay();
			declaredVoteCandidate = null;
			voteCandidate = null;
			declaredAttackCandidate = null;
			attackCandidate = null;
			lastVote = null;
			lastAttackVote = null;
			talkQueue.clear();
			whisperQueue.clear();
			talkTurn = 0;

			// 偽の判定
			if (day > 0) {
				// カミングアウト前は占い結果と霊媒結果の両方用意
				if (!isCameout || fakeRole == Role.SEER) {
					Judge divination = getFakeJudge(Role.SEER);
					if (divination != null) {
						divinationQueue.offer(divination);
						divinedAgents.add(divination.getTarget());
						enqueueWhisper(new Content(new DivineContentBuilder(divination.getTarget(), divination.getResult())));
					}
				}
				if (!isCameout || fakeRole == Role.MEDIUM) {
					Judge inquest = getFakeJudge(Role.MEDIUM);
					if (inquest != null) {
						inquestQueue.offer(inquest);
						enqueueWhisper(new Content(new InquestContentBuilder(inquest.getTarget(), inquest.getResult())));
					}
				}
			}
		}

		currentGameInfo = gameInfo;

		int lastDivIdx = agi.getDivinationList().size();
		int lastInqIdx = agi.getInquestList().size();
		agi.update(currentGameInfo);

		List<Agent> possessedPersons = new ArrayList<>();
		// 占い結果が嘘の場合，裏切り者候補
		for (int i = lastDivIdx; i < agi.getDivinationList().size(); i++) {
			Judge judge = agi.getDivinationList().get(i);
			if ((humans.contains(judge.getTarget()) && judge.getResult() == Species.WEREWOLF) || (werewolves.contains(judge.getTarget()) && judge.getResult() == Species.HUMAN)) {
				if (!possessedPersons.contains(judge.getAgent())) {
					possessedPersons.add(judge.getAgent());
				}
			}
		}
		// 霊媒結果が嘘の場合，裏切り者候補
		for (int i = lastInqIdx; i < agi.getInquestList().size(); i++) {
			Judge judge = agi.getInquestList().get(i);
			if ((humans.contains(judge.getTarget()) && judge.getResult() == Species.WEREWOLF) || (werewolves.contains(judge.getTarget()) && judge.getResult() == Species.HUMAN)) {
				if (!possessedPersons.contains(judge.getAgent())) {
					possessedPersons.add(judge.getAgent());
				}
			}
		}
		if (!possessedPersons.isEmpty()) {
			if (!possessedPersons.contains(possessed)) {
				Collections.shuffle(possessedPersons);
				possessed = possessedPersons.get(0);
				enqueueWhisper(new Content(new EstimateContentBuilder(possessed, Role.POSSESSED)));
			}
		}
	}

	@Override
	public String talk() {

		// まず他の人狼のカミングアウト状況を調べる
		int fakeSeerCO = 0;
		int fakeMediumCO = 0;
		for (Agent wolf : werewolves) {
			if (agi.getComingoutMap().get(wolf) == Role.SEER) {
				fakeSeerCO++;
			} else if (agi.getComingoutMap().get(wolf) == Role.MEDIUM) {
				fakeMediumCO++;
			}
		}

		if (fakeRole == Role.SEER) {
			// カミングアウトする日になったら，あるいは対抗カミングアウトがあればカミングアウト
			if (!isCameout && (day >= comingoutDay || agi.getComingoutMap().containsValue(Role.SEER)) && talkTurn >= comingoutTurn) {
				// 既に偽占い師カミングアウトあり
				if (fakeSeerCO > 0) {
					// 偽霊媒師もカミングアウトありの場合，潜伏人狼
					if (fakeMediumCO > 0) {
						fakeRole = Role.VILLAGER;
						comingoutDay = 10000;
						removeWhisper(Topic.DIVINED);
						removeWhisper(Topic.INQUESTED);
					}
					// 偽霊媒師に転向
					else {
						fakeRole = Role.MEDIUM;
						removeWhisper(Topic.DIVINED);
					}
				}
				// 占い師として出る
				else {
					enqueueTalk(new Content(new ComingoutContentBuilder(me, fakeRole)));
					removeWhisper(Topic.INQUESTED);
					isCameout = true;
				}
			}
		} else if (fakeRole == Role.MEDIUM) {
			// カミングアウトする日になったら，あるいは対抗カミングアウトがあればカミングアウト
			if (!isCameout && (day >= comingoutDay || agi.getComingoutMap().containsValue(Role.MEDIUM)) && talkTurn >= comingoutTurn) {
				// 既に偽霊媒師カミングアウトあり
				if (fakeMediumCO > 0) {
					// 偽占い師もカミングアウトありの場合，潜伏人狼
					if (fakeSeerCO > 0) {
						fakeRole = Role.VILLAGER;
						comingoutDay = 10000;
						removeWhisper(Topic.DIVINED);
						removeWhisper(Topic.INQUESTED);
					}
					// 偽占い師に転向
					else {
						fakeRole = Role.SEER;
						removeWhisper(Topic.INQUESTED);
					}
				}
				// 霊媒師として出る
				else {
					enqueueTalk(new Content(new ComingoutContentBuilder(me, fakeRole)));
					removeWhisper(Topic.DIVINED);
					isCameout = true;
				}
			}
		}

		// カミングアウトしたら，これまでの偽判定結果をすべて公開
		if (isCameout) {
			if (fakeRole == Role.SEER) {
				while (!divinationQueue.isEmpty()) {
					Judge divination = divinationQueue.poll();
					enqueueTalk(new Content(new DivineContentBuilder(divination.getTarget(), divination.getResult())));
				}
			} else if (fakeRole == Role.MEDIUM) {
				while (!inquestQueue.isEmpty()) {
					Judge inquest = inquestQueue.poll();
					enqueueTalk(new Content(new InquestContentBuilder(inquest.getTarget(), inquest.getResult())));
				}
			}
		}

		chooseVoteCandidate();
		// 以前宣言した（未宣言を含む）投票先と違う投票先を選んだ場合宣言する
		if (voteCandidate != declaredVoteCandidate)

		{
			enqueueTalk(new Content(new VoteContentBuilder(voteCandidate)));
			declaredVoteCandidate = voteCandidate;
		}

		talkTurn++;
		return dequeueTalk().getText();
	}

	@Override
	public String whisper() {
		chooseAttackCandidate();
		// 以前宣言した（未宣言を含む）襲撃先と違う襲撃先を選んだ場合宣言する
		if (attackCandidate != declaredAttackCandidate) {
			enqueueWhisper(new Content(new AttackContentBuilder(attackCandidate)));
			declaredAttackCandidate = attackCandidate;
		}

		return dequeueWhisper().getText();
	}

	@Override
	public Agent vote() {
		// 初回投票
		if (lastVote == null) {
			lastVote = new Vote(day, me, voteCandidate);
			return voteCandidate;
		}
		// 再投票：前回最多得票の人間
		Counter<Agent> counter = new Counter<>();
		for (Vote vote : currentGameInfo.getLatestVoteList()) {
			if (humans.contains(vote.getTarget())) {
				counter.add(vote.getTarget());
			}
		}
		int max = counter.get(counter.getLargest());
		List<Agent> candidates = new ArrayList<>();
		for (Agent agent : counter) {
			if (counter.get(agent) == max) {
				candidates.add(agent);
			}
		}
		// 候補がいない場合：村人陣営から
		if (candidates.isEmpty()) {
			candidates.addAll(agi.getAliveOthers());
			candidates.removeAll(werewolves);
			candidates.remove(possessed);
		}
		if (candidates.contains(voteCandidate)) {
			return voteCandidate;
		}
		Collections.shuffle(candidates);
		return candidates.get(0);
	}

	@Override
	public Agent attack() {
		// 初回投票
		if (lastAttackVote == null) {
			lastAttackVote = new Vote(day, me, attackCandidate);
			return attackCandidate;
		}
		// 再投票：前回最多得票数の人間
		Counter<Agent> counter = new Counter<>();
		for (Vote vote : currentGameInfo.getLatestAttackVoteList()) {
			if (humans.contains(vote.getTarget())) {
				counter.add(vote.getTarget());
			}
		}
		int max = counter.get(counter.getLargest());
		List<Agent> candidates = new ArrayList<>();
		for (Agent agent : counter) {
			if (counter.get(agent) == max) {
				candidates.add(agent);
			}
		}
		// 候補がいない場合：村人陣営から
		if (candidates.isEmpty()) {
			candidates.addAll(agi.getAliveOthers());
			candidates.removeAll(werewolves);
			candidates.remove(possessed);
		}
		if (candidates.contains(attackCandidate)) {
			return attackCandidate;
		}
		Collections.shuffle(candidates);
		return candidates.get(0);
	}

	@Override
	public void finish() {
	}

	/**
	 * <div lang="ja">投票先候補を選ぶ</div>
	 *
	 * <div lang="en">Choose a candidate for vote.</div>
	 */
	void chooseVoteCandidate() {
		List<Agent> villagers = new ArrayList<>(agi.getAliveOthers());
		villagers.removeAll(werewolves);
		villagers.remove(possessed);
		List<Agent> candidates = villagers; // 村人騙りの場合は村人陣営から

		// 占い師/霊媒師騙りの場合
		if (fakeRole == Role.SEER || fakeRole == Role.MEDIUM) {
			candidates = new ArrayList<>();
			// 対抗カミングアウトのエージェントは投票先候補
			for (Agent agent : villagers) {
				if (agi.getComingoutMap().containsKey(agent) && agi.getComingoutMap().get(agent) == fakeRole) {
					candidates.add(agent);
				}
			}
			// 人狼と判定したエージェントは投票先候補
			List<Agent> fakeHumans = new ArrayList<>();
			Deque<Judge> judgeQueue = null;
			if (fakeRole == Role.SEER) {
				judgeQueue = divinationQueue;
			} else if (fakeRole == Role.MEDIUM) {
				judgeQueue = inquestQueue;
			}
			for (Judge judge : judgeQueue) {
				if (judge.getResult() == Species.HUMAN) {
					fakeHumans.add(judge.getTarget());
				} else if (!candidates.contains(judge.getTarget())) {
					candidates.add(judge.getTarget());
				}
			}
			// 候補がいなければ人間と判定していない村人陣営から
			if (candidates.isEmpty()) {
				candidates.addAll(villagers);
				candidates.removeAll(fakeHumans);
				// それでも候補がいなければ村人陣営から
				if (candidates.isEmpty()) {
					candidates.addAll(villagers);
				}
			}
		}
		if (candidates.contains(voteCandidate)) {
			return;
		} else {
			Collections.shuffle(candidates);
			voteCandidate = candidates.get(0);
		}
	}

	/**
	 * <div lang="ja">襲撃先候補を選ぶ</div>
	 *
	 * <div lang="en">Choose a candidate for attack.</div>
	 */
	void chooseAttackCandidate() {
		// カミングアウトした村人陣営は襲撃先候補
		List<Agent> villagers = new ArrayList<>(agi.getAliveOthers());
		villagers.removeAll(werewolves);
		villagers.remove(possessed);
		List<Agent> candidates = new ArrayList<>();
		for (Agent agent : villagers) {
			if (agi.getComingoutMap().containsKey(agent)) {
				candidates.add(agent);
			}
		}
		// 候補がいなければ村人陣営から
		if (candidates.isEmpty()) {
			candidates.addAll(villagers);
		}
		if (candidates.contains(attackCandidate)) {
			return;
		}
		Collections.shuffle(candidates);
		attackCandidate = candidates.get(0);
		enqueueWhisper(new Content(new AttackContentBuilder(attackCandidate)));
	}

	/**
	 * <div lang="ja">偽判定を返す</div>
	 *
	 * <div lang="en">Returns the fake judge.</div>
	 * 
	 * @param role
	 *            <div lang="ja">騙る役職を表す{@code Role}</div>
	 *
	 *            <div lang="en">{@code Role} representing the fake role.</div>
	 * 
	 * @return <div lang="ja">偽判定を表す{@code Judge}</div>
	 *
	 *         <div lang="en">{@code Judge} representing the fake judge.</div>
	 */
	Judge getFakeJudge(Role role) {
		Agent target = null;
		Species result = null;
		List<Species> results = new ArrayList<>(Arrays.asList(Species.HUMAN, Species.WEREWOLF));

		// 村人騙りなら不必要
		if (role == Role.VILLAGER) {
			return null;
		}
		// 占い師騙りの場合
		else if (role == Role.SEER) {
			List<Agent> candidates = new ArrayList<>();
			for (Agent agent : agi.getAliveOthers()) {
				if (!divinedAgents.contains(agent) && agi.getComingoutMap().get(agent) != fakeRole) {
					candidates.add(agent);
				}
			}

			if (!candidates.isEmpty()) {
				Collections.shuffle(candidates);
				target = candidates.get(0);
			} else {
				candidates.clear();
				candidates.addAll(agi.getAliveOthers());
				Collections.shuffle(candidates);
				target = candidates.get(0);
			}
			// 人狼が偽占い対象の場合
			if (werewolves.contains(target)) {
				result = Species.HUMAN;
			}
			// 人間が偽占い対象の場合
			else {
				// 裏切り者，あるいはまだカミングアウトしていないエージェントの場合，判定は五分五分
				if (target == possessed || !agi.getComingoutMap().containsKey(target)) {
					Collections.shuffle(results);
					result = results.get(0);
				}
				// それ以外は人狼判定
				else {
					result = Species.WEREWOLF;
				}
			}
		}
		// 霊媒師騙りの場合
		else if (role == Role.MEDIUM) {
			target = currentGameInfo.getExecutedAgent();
			if (target == null) {
				return null;
			}
			// 人狼が霊媒対象の場合
			if (werewolves.contains(target)) {
				result = Species.HUMAN;
			}
			// 人間が偽占い対象の場合
			else {
				// 裏切り者，あるいはまだカミングアウトしていないエージェントの場合，判定は五分五分
				if (target == possessed || !agi.getComingoutMap().containsKey(target)) {
					Collections.shuffle(results);
					result = results.get(0);
				}
				// それ以外は人狼判定
				else {
					result = Species.WEREWOLF;
				}
			}
		}
		return new Judge(day, me, target, result);
	}

	/**
	 * <div lang="ja">発話を待ち行列に入れる</div>
	 *
	 * <div lang="en">Enqueue a utterance.</div>
	 * 
	 * @param newContent
	 *            <div lang="ja">発話を表す{@code Content}</div>
	 *
	 *            <div lang="en">{@code Content} representing the utterance.</div>
	 */
	void enqueueTalk(Content newContent) {
		String newText = newContent.getText();
		Topic newTopic = newContent.getTopic();
		Iterator<Content> it = talkQueue.iterator();
		boolean isEnqueue = true;

		switch (newTopic) {
		case AGREE:
		case DISAGREE:
			// 同一のものが待ち行列になければ入れる
			while (it.hasNext()) {
				if (it.next().getText().equals(newText)) {
					isEnqueue = false;
					break;
				}
			}
			break;

		case COMINGOUT:
			// 同じエージェントについての異なる役職のカミングアウトが待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.COMINGOUT && content.getTarget() == newContent.getTarget()) {
					if (content.getRole() == newContent.getRole()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case ESTIMATE:
			// 同じエージェントについての推測役職が異なる推測発言が待ち行列に残っていればそちらを取り下げ新しい方を待ち行列に入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.ESTIMATE && content.getTarget() == newContent.getTarget()) {
					if (content.getRole() == newContent.getRole()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case DIVINED:
			// 同じエージェントについての異なる占い結果が待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.DIVINED && content.getTarget() == newContent.getTarget()) {
					if (content.getResult() == newContent.getResult()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case INQUESTED:
			// 同じエージェントについての異なる霊媒結果が待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.INQUESTED && content.getTarget() == newContent.getTarget()) {
					if (content.getResult() == newContent.getResult()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case VOTE:
			// 異なる投票先宣言が待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.VOTE) {
					if (content.getTarget() == newContent.getTarget()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		default:
			break;
		}

		if (isEnqueue) {
			if (newContent.getTopic() == Topic.ESTIMATE) {
				// 過去の推測発言で同一のものには同意発言，相反するものには不同意発言
				if (agi.getEstimateMap().containsKey(newContent.getTarget())) {
					for (Talk talk : agi.getEstimateMap().get(newContent.getTarget())) {
						Content pastContent = new Content(talk.getText());
						if (pastContent.getRole() == newContent.getRole()) {
							enqueueTalk(new Content(new AgreeContentBuilder(TalkType.TALK, talk.getDay(), talk.getIdx())));
						} else {
							enqueueTalk(new Content(new DisagreeContentBuilder(TalkType.TALK, talk.getDay(), talk.getIdx())));
						}
					}
				}
			}
			talkQueue.offer(newContent);
		}
	}

	/**
	 * <div lang="ja">発話を待ち行列から取り出す</div>
	 *
	 * <div lang="en">Dequeue a utterance.</div>
	 * 
	 * @return <div lang="ja">発話を表す{@code Content}</div>
	 *
	 *         <div lang="en">{@code Content} representing the utterance.</div>
	 */
	Content dequeueTalk() {
		if (talkQueue.isEmpty()) {
			return Content.SKIP;
		}
		return talkQueue.poll();
	}

	/**
	 * <div lang="ja">囁きを待ち行列に入れる</div>
	 *
	 * <div lang="en">Enqueue a whispered utterance.</div>
	 * 
	 * @param newContent
	 *            <div lang="ja">発話を表す{@code Content}</div>
	 *
	 *            <div lang="en">{@code Content} representing the utterance.</div>
	 */
	void enqueueWhisper(Content newContent) {
		String newText = newContent.getText();
		Topic newTopic = newContent.getTopic();
		Iterator<Content> it = whisperQueue.iterator();
		boolean isEnqueue = true;

		switch (newTopic) {
		case AGREE:
		case DISAGREE:
			// 同一のものが待ち行列になければ入れる
			while (it.hasNext()) {
				if (it.next().getText().equals(newText)) {
					isEnqueue = false;
					break;
				}
			}
			break;

		case COMINGOUT:
			// 同じエージェントについての異なる役職のカミングアウトが待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.COMINGOUT && content.getTarget() == newContent.getTarget()) {
					if (content.getRole() == newContent.getRole()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case ESTIMATE:
			// 同じエージェントについての推測役職が異なる推測発言が待ち行列に残っていればそちらを取り下げ新しい方を待ち行列に入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.ESTIMATE && content.getTarget() == newContent.getTarget()) {
					if (content.getRole() == newContent.getRole()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case DIVINED:
			// 同じエージェントについての異なる占い結果が待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.DIVINED && content.getTarget() == newContent.getTarget()) {
					if (content.getResult() == newContent.getResult()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case INQUESTED:
			// 同じエージェントについての異なる霊媒結果が待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.INQUESTED && content.getTarget() == newContent.getTarget()) {
					if (content.getResult() == newContent.getResult()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case VOTE:
			// 異なる投票先宣言が待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.VOTE) {
					if (content.getTarget() == newContent.getTarget()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		case ATTACK:
			// 異なる襲撃先宣言が待ち行列に残っていればそれを取り下げて新しい方を入れる
			while (it.hasNext()) {
				Content content = it.next();
				if (content.getTopic() == Topic.ATTACK) {
					if (content.getTarget() == newContent.getTarget()) {
						isEnqueue = false;
						break;
					} else {
						it.remove();
					}
				}
			}
			break;

		default:
			break;
		}

		if (isEnqueue) {
			whisperQueue.offer(newContent);
		}
	}

	/**
	 * <div lang="ja">囁きを待ち行列から取り出す</div>
	 *
	 * <div lang="en">Dequeue a whispered utterance.</div>
	 * 
	 * @return <div lang="ja">発話を表す{@code Content}</div>
	 *
	 *         <div lang="en">{@code Content} representing the utterance.</div>
	 */
	Content dequeueWhisper() {
		if (whisperQueue.isEmpty()) {
			return Content.SKIP;
		}
		return whisperQueue.poll();
	}

	/**
	 * <div lang="ja">指定したトピックの囁きを取り除く</div>
	 *
	 * <div lang="en">Remove the whispers of given topic.</div>
	 * 
	 * @param topic
	 *            <div lang="ja">取り除くトピックを表す{@code Topic}</div>
	 *
	 *            <div lang="en">{@code Topic} representing the topic to be removed from the queue.</div>
	 */
	void removeWhisper(Topic topic) {
		Iterator<Content> it = whisperQueue.iterator();
		while (it.hasNext()) {
			Content content = it.next();
			if (content.getTopic() == topic) {
				it.remove();
			}
		}
	}

}
